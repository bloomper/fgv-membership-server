package nu.fgv.membership.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call what, with real tokens from Keycloak: the roles are a ladder, and a role's holder can do what the ones
 * below it can.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void theApplicationStartsWithAllItsMigrations() {
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success").query(Long.class).single()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void graphqlNeedsAToken() {
        http().post().uri("/api/graphql").bodyValue("{\"query\":\"{ myMembership { linked } }\"}").header("Content-Type", "application/json")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aMemberCanReadTheirOwnMembershipButNotUseTheBoardsOrAdminsFunctions() {
        spexregister("medlemskapSpexareForUser", "{\"medlemskapSpexareForUser\":" + person(SPEXARE_ID, "Greta", "Test", MEMBER) + "}");

        graphQl(userToken(MEMBER)).document("{ myMembership { linked memberships { year } } }").execute().errors().verify()
                .path("myMembership.linked").entity(Boolean.class).isEqualTo(true);
        graphQl(userToken(MEMBER)).document("{ memberTrend { year } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(MEMBER)).document("{ waitingPaymentRequests { id } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(MEMBER)).document("{ users { total } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(MEMBER)).document("{ settings { reminderDays } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void eachRoleReachesWhatItsOwnAndTheOnesBelowItAllow() {
        graphQl(userToken(BOARD)).document("{ memberTrend { year } }").execute().errors().verify();
        graphQl(userToken(BOARD)).document("{ waitingPaymentRequests { id } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(TREASURER)).document("{ memberTrend { year } }").execute().errors().verify();
        graphQl(userToken(TREASURER)).document("{ waitingPaymentRequests { id } }").execute().errors().verify();
        graphQl(userToken(TREASURER)).document("{ allFeeLevels { id } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(ADMIN)).document("{ allFeeLevels { id } }").execute().errors().verify();
        graphQl(userToken(ADMIN)).document("{ waitingPaymentRequests { id } }").execute().errors().verify();
        graphQl(userToken(ADMIN)).document("{ users { total } }").execute().errors().verify().path("users.total").entity(Integer.class).satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(4));
    }

    @Test
    void serviceAccountsOnlyDoWhatTheirRoleAllows() {
        graphQl(serviceToken("fgv-spexregister-service")).document("{ membershipChanges { hasMore } }").execute().errors().verify();
        graphQl(serviceToken("fgv-revival-service")).document("{ membershipChanges { hasMore } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(serviceToken("fgv-revival-service")).document("{ revivalFeeLevels { id } }").execute().errors().verify();
        graphQl(serviceToken("fgv-spexregister-service")).document("{ revivalFeeLevels { id } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(serviceToken("fgv-wordpress-service")).document("{ myMembership { linked } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void theWordpressShopMayOnlyTellAboutPayments() {
        http().post().uri("/api/external-payments").header("Authorization", userToken(ADMIN)).header("Content-Type", "application/json")
                .bodyValue("{}").exchange().expectStatus().isForbidden();
        http().post().uri("/api/external-payments").header("Content-Type", "application/json").bodyValue("{}").exchange().expectStatus().isUnauthorized();
    }
}
