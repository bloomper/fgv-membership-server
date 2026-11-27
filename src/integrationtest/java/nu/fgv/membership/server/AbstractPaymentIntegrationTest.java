package nu.fgv.membership.server;

import java.time.Year;

/**
 * What the tests of paying share: Spexregister knows the member, the admin has set a fee, the member has asked to pay.
 */
abstract class AbstractPaymentIntegrationTest extends AbstractIntegrationTest {

    protected static final int YEAR = Year.now().getValue();

    /**
     * Spexregister knows one spexare, linked to {@link #MEMBER}'s account, and answers for her by id.
     */
    protected static void spexregisterKnowsTheMember() {
        spexregister("medlemskapSpexareForUser", "{\"medlemskapSpexareForUser\":" + person(SPEXARE_ID, "Greta", "Test", MEMBER) + "}");
        spexregister("medlemskapSpexareByIds", "{\"medlemskapSpexareByIds\":[" + person(SPEXARE_ID, "Greta", "Test", MEMBER) + "]}");
    }

    protected String createFee(final String kind, final int year, final int amount) {
        return graphQl(userToken(ADMIN)).document("""
                        mutation($input: FeeLevelInput!) { feeLevelSave(input: $input) { id } }""")
                .variable("input", java.util.Map.of("kind", kind, "year", year, "nameSv", kind.toLowerCase() + " " + year, "nameEn", kind.toLowerCase() + " " + year, "amount", amount))
                .execute().errors().verify().path("feeLevelSave.id").entity(String.class).get();
    }

    protected String requestPayment(final String... feeIds) {
        return graphQl(userToken(MEMBER)).document("mutation($fees: [ID!]!) { requestPayment(feeLevelIds: $fees) { id } }")
                .variable("fees", java.util.List.of(feeIds)).execute().errors().verify().path("requestPayment.id").entity(String.class).get();
    }

    protected String referenceOf(final String requestId) {
        return graphQl(userToken(MEMBER)).document("query($id: ID!) { paymentRequest(id: $id) { reference } }").variable("id", requestId)
                .execute().errors().verify().path("paymentRequest.reference").entity(String.class).get();
    }

    protected void record(final String requestId, final int amount) {
        graphQl(userToken(TREASURER)).document("""
                        mutation($id: ID!, $date: String!, $amount: Int!) { recordPayment(requestId: $id, paidOn: $date, amount: $amount, how: SWISH) { status } }""")
                .variable("id", requestId).variable("date", YEAR + "-06-01").variable("amount", amount).execute().errors().verify();
    }
}
