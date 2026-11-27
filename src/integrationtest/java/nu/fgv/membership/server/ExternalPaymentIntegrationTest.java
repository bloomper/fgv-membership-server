package nu.fgv.membership.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An external application tells that someone paid: a new person gets a spexare, an account with the default access, two mails
 * (one from Medlemskap, one from Keycloak with the link to choose a password) and a membership; and it can be asked who has
 * an account and a membership.
 */
class ExternalPaymentIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW = "butik@membership.test";
    private static final String SHOP = "fgv-external-payment-service";

    private String order(final String email, final String reference, final String extra) {
        return "{\"email\":\"" + email + "\",\"firstName\":\"Bo\",\"lastName\":\"Butik\",\"year\":" + java.time.Year.now().getValue()
                + ",\"amount\":200,\"paymentReference\":\"" + reference + "\"" + extra + "}";
    }

    @BeforeEach
    void spexregisterDoesNotKnowThePerson() {
        spexregister("medlemskapSpexareByEmail", "{\"medlemskapSpexareByEmail\":null}");
        spexregister("medlemskapSpexareCreate", "{\"medlemskapSpexareCreate\":" + person(SPEXARE_ID, "Bo", "Butik", NEW) + "}");
        spexregister("medlemskapSpexareByIds", "{\"medlemskapSpexareByIds\":[" + person(SPEXARE_ID, "Bo", "Butik", NEW) + "]}");
    }

    @Test
    void aNewPersonGetsASpexareAnAccountWithTheDefaultAccessMailAndAMembership() {
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order(NEW, "order-1", "")).exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("RECORDED").jsonPath("$.accountCreated").isEqualTo(true);

        assertThat(userExists(NEW)).isTrue();
        // The full memberships' default access: the lowest role everywhere except Nyuppsättning
        assertThat(roles(NEW, "fgv-spexregister")).containsExactly("USER");
        assertThat(roles(NEW, "fgv-membership")).containsExactly("USER");
        assertThat(roles(NEW, "fgv-revival")).isEmpty();
        assertThat(inGroup(NEW, "pending-approval")).isFalse();

        // One mail from Medlemskap, the receipt, and Keycloak's with the link to choose a password
        assertThat(body(mailWhere(message -> subject(message).contains("skapats")))).contains("https://membership.test");
        assertThat(body(mailWhere(message -> subject(message).contains("Kvitto")))).contains("200");
        assertThat(recipients(mailWhere(message -> recipients(message).stream().anyMatch(r -> r.contains(NEW)) && body(message).contains("login-actions")))).isNotEmpty();
        mailTo("admin@membership.test");

        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("SELECT how FROM membership").query(String.class).single()).isEqualTo("ONLINE");
        assertThat(jdbcClient.sql("SELECT status FROM payment_request").query(String.class).single()).isEqualTo("MATCHED");
    }

    @Test
    void theApplicationsOwnIdForThePaymentIsKeptAndSeenByTheTreasurer() {
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order("id@membership.test", "klarna-7731", "")).exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.paymentReference").isEqualTo("klarna-7731");

        assertThat(jdbcClient.sql("SELECT CONCAT(external_source, ' ', external_reference) FROM payment_request").query(String.class).single())
                .isEqualTo(SHOP + " klarna-7731");
        graphQl(userToken(TREASURER)).document("{ paymentsThisYear { externalReference how } }").execute().errors().verify()
                .path("paymentsThisYear[0].externalReference").entity(String.class).isEqualTo(SHOP + " klarna-7731")
                .path("paymentsThisYear[0].how").entity(String.class).isEqualTo("ONLINE");
    }

    @Test
    void thePaymentIdIsOptionalButWithoutOneNothingStopsATellingTwice() {
        final String body = "{\"email\":\"utan@membership.test\",\"firstName\":\"Ut\",\"lastName\":\"Anid\",\"year\":" + java.time.Year.now().getValue() + ",\"amount\":200}";

        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json").bodyValue(body)
                .exchange().expectStatus().isCreated();
        // The second one is not a duplicate of anything we know, but the person is a member already
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json").bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ALREADY_MEMBER");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM payment").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void tellingTheSameOrderAgainChangesNothing() {
        final String body = order("igen@membership.test", "order-2", "");

        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json").bodyValue(body)
                .exchange().expectStatus().isCreated();
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json").bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("DUPLICATE");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM membership").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM payment").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void someoneWhoAlreadyHasAnAccountKeepsItAsItWas() {
        final String existing = "finns@membership.test";

        createUser(existing, "Finns", java.util.List.of());
        spexregister("medlemskapSpexareByEmail", "{\"medlemskapSpexareByEmail\":" + person(SPEXARE_ID, "Bo", "Butik", existing) + "}");

        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order(existing, "order-3", "")).exchange().expectStatus().isCreated().expectBody().jsonPath("$.accountCreated").isEqualTo(false);
        assertThat(roles(existing, "fgv-spexregister")).isEmpty();
    }

    @Test
    void aSupportMembershipGivesTheSupportAccessOnly() {
        final String support = "stod@membership.test";

        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order(support, "order-4", ",\"kind\":\"SUPPORT\"")).exchange().expectStatus().isCreated();

        assertThat(roles(support, "fgv-spexipedia")).containsExactly("USER");
        assertThat(roles(support, "fgv-spexflix")).containsExactly("USER");
        assertThat(roles(support, "fgv-spexgram")).containsExactly("USER");
        assertThat(roles(support, "fgv-spexify")).containsExactly("USER");
        assertThat(roles(support, "fgv-spexregister")).isEmpty();
        assertThat(roles(support, "fgv-revival")).isEmpty();
        assertThat(jdbcClient.sql("SELECT support FROM membership").query(Boolean.class).single()).isTrue();
    }

    @Test
    void anAdminCanChangeWhatTheDefaultAccessIs() {
        graphQl(userToken(ADMIN)).document("mutation { setDefaultAccess(profile: MEMBER, clientId: \"fgv-spexregister\", role: \"EDITOR\") { role } }").execute().errors().verify();
        graphQl(userToken(ADMIN)).document("mutation { setDefaultAccess(profile: MEMBER, clientId: \"fgv-membership\", role: null) { role } }").execute().errors().verify();

        final String changed = "andrad@membership.test";

        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order(changed, "order-5", "")).exchange().expectStatus().isCreated();
        assertThat(roles(changed, "fgv-spexregister")).containsExactly("EDITOR", "USER");
        // USER on Medlemskap still comes with Keycloak's default role
        assertThat(roles(changed, "fgv-membership")).containsExactly("USER");
    }

    @Test
    void invalidOrdersAreRefusedAndFamilyMembershipsAreNotSoldInTheShop() {
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order("inte-en-adress", "order-6", "")).exchange().expectStatus().isBadRequest();
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order("fam@membership.test", "order-7", ",\"kind\":\"FAMILY\"")).exchange().expectStatus().isBadRequest();
        assertThat(userExists("fam@membership.test")).isFalse();
    }

    @Test
    void ifSpexregisterIsDownNothingIsRecordedSoTheShopCanRetry() {
        SPEXREGISTER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/spexregister/graphql"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));

        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order("retry@membership.test", "order-8", "")).exchange().expectStatus().is5xxServerError();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM external_payment").query(Long.class).single()).isZero();
        assertThat(userExists("retry@membership.test")).isFalse();
    }

    @Test
    void anApplicationCanAskWhetherAnAddressHasAnAccountAndAMembership() {
        final String paid = "betald@membership.test";

        spexregister("medlemskapSpexareByEmail", "{\"medlemskapSpexareByEmail\":" + person(SPEXARE_ID, "Bo", "Betald", paid) + "}");
        http().post().uri("/api/external/payments").header("Authorization", serviceToken(SHOP)).header("Content-Type", "application/json")
                .bodyValue(order(paid, "order-20", "")).exchange().expectStatus().isCreated();

        http().get().uri("/api/external/lookup?email=" + paid).header("Authorization", serviceToken(SHOP)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.account.exists").isEqualTo(true).jsonPath("$.account.enabled").isEqualTo(true).jsonPath("$.spexare").isEqualTo(true)
                .jsonPath("$.member").isEqualTo(true).jsonPath("$.active.kind").isEqualTo("STANDARD").jsonPath("$.active.status").isEqualTo("PAID")
                .jsonPath("$.year").isEqualTo(java.time.Year.now().getValue());
        // Another year the same person is not a member
        http().get().uri("/api/external/lookup?email=" + paid + "&year=" + (java.time.Year.now().getValue() - 5)).header("Authorization", serviceToken(SHOP)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.member").isEqualTo(false).jsonPath("$.account.exists").isEqualTo(true);
    }

    @Test
    void anAddressNobodyKnowsHasNothing() {
        spexregister("medlemskapSpexareByEmail", "{\"medlemskapSpexareByEmail\":null}");

        http().get().uri("/api/external/lookup?email=ingen@membership.test").header("Authorization", serviceToken(SHOP)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.account.exists").isEqualTo(false).jsonPath("$.spexare").isEqualTo(false).jsonPath("$.member").isEqualTo(false).jsonPath("$.active").isEmpty();
    }

    @Test
    void theLookupIsOnlyForApplicationsThatMayTellAboutPayments() {
        http().get().uri("/api/external/lookup?email=x@membership.test").header("Authorization", userToken(ADMIN)).exchange().expectStatus().isForbidden();
        http().get().uri("/api/external/lookup?email=x@membership.test").header("Authorization", serviceToken("fgv-revival-service")).exchange().expectStatus().isForbidden();
        http().get().uri("/api/external/lookup?email=x@membership.test").exchange().expectStatus().isUnauthorized();
        http().get().uri("/api/external/lookup?email=inte-en-adress").header("Authorization", serviceToken(SHOP)).exchange().expectStatus().isBadRequest();
    }

    @Test
    void ifSpexregisterCannotBeReachedTheLookupSaysSoInsteadOfGuessing() {
        SPEXREGISTER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/spexregister/graphql"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));

        http().get().uri("/api/external/lookup?email=x@membership.test").header("Authorization", serviceToken(SHOP)).exchange().expectStatus().isEqualTo(503);
    }
}
