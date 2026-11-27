package nu.fgv.membership.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the board does: the members list and its counts, waivers, payment links that need no login, renewal reminders,
 * payments recorded without a request, and the Excel exports.
 */
class BoardIntegrationTest extends AbstractPaymentIntegrationTest {

    private static final Pattern LINK = Pattern.compile("/pay/([A-Za-z0-9_-]{40,})");

    @BeforeEach
    void knownMember() {
        spexregisterKnowsTheMember();
    }

    private String sendLink(final String... fees) {
        graphQl(userToken(BOARD)).document("mutation($s: ID!, $f: [ID!]!) { sendPaymentLink(spexareId: $s, feeLevelIds: $f) }")
                .variable("s", SPEXARE_ID).variable("f", List.of(fees)).execute().errors().verify().path("sendPaymentLink").entity(Boolean.class).isEqualTo(true);

        final Matcher matcher = LINK.matcher(body(mailTo(MEMBER)));

        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Test
    void aPersonalLinkOpensTheRequestWithoutLoginAndLetsThePayerSayTheyHavePaid() {
        final String token = sendLink(createFee("STANDARD", YEAR, 200));

        http().get().uri("/api/personal/" + token).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.amount").isEqualTo(200).jsonPath("$.status").isEqualTo("OPEN").jsonPath("$.bankgiro").isEqualTo("123-4567")
                .jsonPath("$.swishLink").value(link -> assertThat(String.valueOf(link)).startsWith("swish://"));
        http().post().uri("/api/personal/" + token + "/report").header("Content-Type", "application/json").bodyValue("{\"paidBy\":\"Maken\"}").exchange().expectStatus().isOk();

        assertThat(body(mailTo("admin@membership.test"))).contains("Maken");
        assertThat(jdbcClient.sql("SELECT status FROM payment_request").query(String.class).single()).isEqualTo("REPORTED");
        // Only the hash of the token is kept, and the link is in the member's page of emails sent
        assertThat(jdbcClient.sql("SELECT token_hash FROM personal_link").query(String.class).single()).isNotEqualTo(token).hasSize(64);
        graphQl(userToken(BOARD)).document("query($s: ID!) { memberDetail(spexareId: $s) { emails { kind } requests { status } } }").variable("s", SPEXARE_ID)
                .execute().errors().verify().path("memberDetail.emails[0].kind").entity(String.class).isEqualTo("LINK");
    }

    @Test
    void aLinkThatIsWrongOrNotThereOpensNothingAndOptingOutIsRemembered() {
        final String token = sendLink(createFee("STANDARD", YEAR, 200));

        http().get().uri("/api/personal/" + "x".repeat(43)).exchange().expectStatus().isNotFound();
        http().get().uri("/api/personal/short").exchange().expectStatus().isNotFound();
        http().post().uri("/api/personal/" + token + "/opt-out").exchange().expectStatus().isOk();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM reminder_opt_out WHERE spexare_id = ?").param(SPEXARE_ID).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void renewalRemindersGoToLastYearsMembersWhoHaveNothingForThisYearAndNotToThoseWhoOptedOut() {
        final String lastYear = createFee("STANDARD", YEAR - 1, 200);
        final String thisYear = createFee("STANDARD", YEAR, 220);

        record(requestPayment(lastYear), 200);
        MAIL.getReceivedMessages();

        graphQl(userToken(BOARD)).document("query($y: Int!, $f: ID!) { renewalPreview(year: $y, feeLevelId: $f) { count sample } }").variable("y", YEAR).variable("f", thisYear)
                .execute().errors().verify().path("renewalPreview.count").entity(Integer.class).isEqualTo(1)
                .path("renewalPreview.sample[0]").entity(String.class).isEqualTo("Greta Test");

        graphQl(userToken(BOARD)).document("mutation($y: Int!, $f: ID!) { sendRenewals(year: $y, feeLevelId: $f) { sent skipped } }").variable("y", YEAR).variable("f", thisYear)
                .execute().errors().verify().path("sendRenewals.sent").entity(Integer.class).isEqualTo(1);

        final var reminder = mailWhere(message -> subject(message).contains("Förnya"));

        assertThat(body(reminder)).contains("220 kr").contains("/pay/");

        final String token = LINK.matcher(body(reminder)).results().findFirst().orElseThrow().group(1);

        // Sending again reaches nobody: the member has a request for the year now
        graphQl(userToken(BOARD)).document("query($y: Int!, $f: ID!) { renewalPreview(year: $y, feeLevelId: $f) { count } }").variable("y", YEAR).variable("f", thisYear)
                .execute().errors().verify().path("renewalPreview.count").entity(Integer.class).isEqualTo(0);
        http().post().uri("/api/personal/" + token + "/opt-out").exchange().expectStatus().isOk();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM reminder_email WHERE kind = 'RENEWAL'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aWaiverMakesAMemberWithoutAFeeAndNeedsANote() {
        graphQl(userToken(BOARD)).document("mutation($s: ID!, $y: Int!) { grantWaiver(spexareId: $s, year: $y, lifetime: false, note: \"Hedersmedlem\") { status how } }")
                .variable("s", SPEXARE_ID).variable("y", YEAR).execute().errors().verify().path("grantWaiver.status").entity(String.class).isEqualTo("WAIVED");
        graphQl(userToken(BOARD)).document("mutation($s: ID!, $y: Int!) { grantWaiver(spexareId: $s, year: $y, lifetime: false, note: \" \") { status } }")
                .variable("s", SPEXARE_ID).variable("y", YEAR + 1).execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(BOARD)).document("query($y: Int!) { members(year: $y) { name status how note } memberTrend { year members joined } }").variable("y", YEAR).execute().errors().verify()
                .path("members[0].name").entity(String.class).isEqualTo("Greta Test")
                .path("members[0].note").entity(String.class).isEqualTo("Hedersmedlem");
        // The feed tells Spexregister about a waived membership as well
        assertThat(jdbcClient.sql("SELECT kind FROM membership_change").query(String.class).single()).isEqualTo("UPSERT");
    }

    @Test
    void aLifeMemberCountsInEveryYearFromTheFirstAndIsNotRemindedToRenew() {
        graphQl(userToken(BOARD)).document("mutation($s: ID!, $y: Int!) { grantWaiver(spexareId: $s, year: $y, lifetime: true, note: \"Livstid\") { lifetime } }")
                .variable("s", SPEXARE_ID).variable("y", YEAR - 3).execute().errors().verify();

        graphQl(userToken(BOARD)).document("query($y: Int!) { members(year: $y) { lifetime year } }").variable("y", YEAR).execute().errors().verify()
                .path("members[0].lifetime").entity(Boolean.class).isEqualTo(true);
        graphQl(userToken(BOARD)).document("{ memberTrend { year members lifetime } }").execute().errors().verify()
                .path("memberTrend[?(@.year == " + YEAR + ")].lifetime").entityList(Integer.class).contains(1);
    }

    @Test
    void aPaymentThatHadNoRequestIsRecordedFromTheMembersPage() {
        final String fee = createFee("STANDARD", YEAR, 200);

        graphQl(userToken(TREASURER)).document("mutation($s: ID!, $f: [ID!]!, $d: String!) { recordPaymentWithoutRequest(spexareId: $s, feeLevelIds: $f, paidOn: $d, amount: 200, how: CASH) { status reference } }")
                .variable("s", SPEXARE_ID).variable("f", List.of(fee)).variable("d", YEAR + "-02-01").execute().errors().verify()
                .path("recordPaymentWithoutRequest.status").entity(String.class).isEqualTo("MATCHED");
        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_entry WHERE action = 'PAYMENT_WITHOUT_REQUEST'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theExcelListsAreRealWorkbooksForTheRightRoles() {
        record(requestPayment(createFee("STANDARD", YEAR, 200)), 200);

        final byte[] members = http().get().uri("/api/exports/members.xlsx?year=" + YEAR).header("Authorization", userToken(BOARD)).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectBody(byte[].class).returnResult().getResponseBody();

        assertThat(members).startsWith((byte) 'P', (byte) 'K');
        http().get().uri("/api/exports/payments.xlsx?year=" + YEAR).header("Authorization", userToken(TREASURER)).exchange().expectStatus().isOk();
        // The board may not take the bookkeeping, nor a member the member list
        http().get().uri("/api/exports/payments.xlsx?year=" + YEAR).header("Authorization", userToken(BOARD)).exchange().expectStatus().isForbidden();
        http().get().uri("/api/exports/members.xlsx?year=" + YEAR).header("Authorization", userToken(MEMBER)).exchange().expectStatus().isForbidden();
    }
}
