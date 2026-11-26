package nu.fgv.membership.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member asks to pay, says they have paid, and the treasurer records it: the membership counts, the spexare gets a
 * receipt, the administrators are told, and Spexregister's copy follows through the feed.
 */
class PaymentFlowIntegrationTest extends AbstractPaymentIntegrationTest {

    @BeforeEach
    void knownMember() {
        spexregisterKnowsTheMember();
    }

    @Test
    void theWholeWayFromRequestToAMembershipThatSpexregisterIsTold() throws Exception {
        final String fee = createFee("STANDARD", YEAR, 200);
        final String request = graphQl(userToken(MEMBER)).document("""
                        mutation($fees: [ID!]!) { requestPayment(feeLevelIds: $fees) { id reference amount status } }""")
                .variable("fees", java.util.List.of(fee)).execute().errors().verify()
                .path("requestPayment.amount").entity(Integer.class).isEqualTo(200)
                .path("requestPayment.status").entity(String.class).isEqualTo("OPEN")
                .path("requestPayment.id").entity(String.class).get();

        // The payment page: Swish link and the bankgiro
        graphQl(userToken(MEMBER)).document("query($id: ID!) { paymentRequest(id: $id) { reference swishLink payee { swishNumber bankgiro bankAccount } memberships { year status } } }")
                .variable("id", request).execute().errors().verify()
                .path("paymentRequest.swishLink").entity(String.class).satisfies(link -> assertThat(link).startsWith("swish://payment?data="))
                .path("paymentRequest.payee.bankgiro").entity(String.class).isEqualTo("123-4567")
                .path("paymentRequest.memberships[0].status").entity(String.class).isEqualTo("PENDING");

        // The member says they paid: the administrators are told
        graphQl(userToken(MEMBER)).document("mutation($id: ID!) { reportPaid(id: $id, paidBy: \"Maken\") { status } }").variable("id", request)
                .execute().errors().verify().path("reportPaid.status").entity(String.class).isEqualTo("REPORTED");

        final var notice = mailTo("admin@membership.test");

        assertThat(body(notice)).contains("Greta Test").contains("Maken").contains("200");

        // The treasurer sees it, with the name from Spexregister
        graphQl(userToken(TREASURER)).document("{ waitingPaymentRequests { id spexareName status } }").execute().errors().verify()
                .path("waitingPaymentRequests[0].spexareName").entity(String.class).isEqualTo("Greta Test");

        record(request, 200);

        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PAID");
        assertThat(body(mailWhere(message -> subject(message).contains("Kvitto")))).contains("200");
        graphQl(userToken(MEMBER)).document("{ myMembership { memberships { year status } requests { status paid } } }").execute().errors().verify()
                .path("myMembership.memberships[0].status").entity(String.class).isEqualTo("PAID")
                .path("myMembership.requests[0].paid").entity(Integer.class).isEqualTo(200);

        // Spexregister reads the feed, and what it has read is remembered
        final String feed = "query($after: String) { membershipChanges(after: $after) { cursor hasMore changes { kind spexareId type year lifetime } } }";
        final var spexregisterToken = serviceToken("fgv-spexregister-service");
        final String cursor = graphQl(spexregisterToken).document(feed).execute().errors().verify()
                .path("membershipChanges.changes").entityList(Object.class).hasSize(1)
                .path("membershipChanges.changes[0].kind").entity(String.class).isEqualTo("UPSERT")
                .path("membershipChanges.changes[0].spexareId").entity(String.class).isEqualTo(SPEXARE_ID)
                .path("membershipChanges.changes[0].year").entity(Integer.class).isEqualTo(YEAR)
                .path("membershipChanges.cursor").entity(String.class).get();

        graphQl(spexregisterToken).document(feed).variable("after", cursor).execute().errors().verify()
                .path("membershipChanges.changes").entityList(Object.class).hasSize(0);
        assertThat(jdbcClient.sql("SELECT acknowledged FROM sync_consumer WHERE client_id = 'fgv-spexregister-service'").query(Long.class).single()).isPositive();
    }

    @Test
    void undoingThePaymentTakesTheMembershipAwayAndTheFeedSaysSo() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));

        record(request, 200);

        final String payment = graphQl(userToken(TREASURER)).document("{ paymentsThisYear { id reference amount } }").execute().errors().verify()
                .path("paymentsThisYear[0].id").entity(String.class).get();

        graphQl(userToken(TREASURER)).document("mutation($id: ID!) { undoPayment(id: $id) }").variable("id", payment).execute().errors().verify();

        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbcClient.sql("SELECT kind FROM membership_change ORDER BY sequence").query(String.class).list()).containsExactly("UPSERT", "DELETE");
    }

    @Test
    void aPartPaymentLeavesTheRequestOpenUntilTheRestArrives() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));

        record(request, 150);
        graphQl(userToken(TREASURER)).document("{ waitingPaymentRequests { status paid amount } }").execute().errors().verify()
                .path("waitingPaymentRequests[0].paid").entity(Integer.class).isEqualTo(150);
        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PENDING");

        record(request, 50);
        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PAID");
    }

    @Test
    void aFamilyPaysOnceAndTheBoardGivesTheOthersTheMembership() {
        final String other = "0197d6a0-0000-7000-8000-000000000002";
        final String family = createFee("FAMILY", YEAR, 350);
        final String request = graphQl(userToken(MEMBER)).document("""
                        mutation($fees: [ID!]!) { requestPayment(feeLevelIds: $fees, familyNote: "Maken Gösta") { id family familyIncomplete amount } }""")
                .variable("fees", java.util.List.of(family)).execute().errors().verify()
                .path("requestPayment.familyIncomplete").entity(Boolean.class).isEqualTo(true)
                .path("requestPayment.id").entity(String.class).get();

        record(request, 350);
        spexregister("medlemskapSpexareByIds", "{\"medlemskapSpexareByIds\":[" + person(SPEXARE_ID, "Greta", "Test", MEMBER) + "," + person(other, "Gösta", "Test", null) + "]}");
        graphQl(userToken(BOARD)).document("mutation($id: ID!, $ids: [ID!]!) { addFamilyMembers(requestId: $id, spexareIds: $ids) { familyIncomplete memberships { status spexareId } } }")
                .variable("id", request).variable("ids", java.util.List.of(other)).execute().errors().verify()
                .path("addFamilyMembers.familyIncomplete").entity(Boolean.class).isEqualTo(false);

        assertThat(jdbcClient.sql("SELECT status FROM membership WHERE spexare_id = ?").param(other).query(String.class).single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM membership_change").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(DISTINCT family_id) FROM membership").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aSupportMembershipIsMarkedAsOne() {
        final String request = requestPayment(createFee("SUPPORT", YEAR, 100));

        record(request, 100);

        assertThat(jdbcClient.sql("SELECT support FROM membership").query(Boolean.class).single()).isTrue();
        graphQl(userToken(BOARD)).document("query($y: Int!) { members(year: $y) { support family } }").variable("y", YEAR).execute().errors().verify()
                .path("members[0].support").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void youCannotPayForAYearYouAreAMemberIn() {
        final String fee = createFee("STANDARD", YEAR, 200);

        record(requestPayment(fee), 200);
        graphQl(userToken(MEMBER)).document("mutation($fees: [ID!]!) { requestPayment(feeLevelIds: $fees) { id } }").variable("fees", java.util.List.of(fee))
                .execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void anotherMemberCannotReadOrReportMyRequest() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));

        spexregister("medlemskapSpexareForUser", "{\"medlemskapSpexareForUser\":" + person("0197d6a0-0000-7000-8000-0000000000ff", "Annan", "Person", null) + "}");
        graphQl(userToken(BOARD)).document("query($id: ID!) { paymentRequest(id: $id) { reference } }").variable("id", request)
                .execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(BOARD)).document("mutation($id: ID!) { reportPaid(id: $id) { status } }").variable("id", request)
                .execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }
}
