package nu.fgv.membership.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing a bank statement: a transaction with a valid reference that covers the request is matched at once, the rest
 * is suggested or matched by hand, and importing the same file again adds nothing.
 */
class BankIntegrationTest extends AbstractPaymentIntegrationTest {

    private String format;

    @BeforeEach
    void bankFormat() {
        spexregisterKnowsTheMember();
        format = graphQl(userToken(ADMIN)).document("mutation($input: BankFormatInput!) { bankFormatSave(input: $input) { id } }")
                .variable("input", Map.of("name", "Testbanken " + System.nanoTime(), "charset", "UTF-8", "delimiter", ";", "headerLines", 1, "dateColumn", 1,
                        "amountColumn", 2, "textColumn", 3, "payerColumn", 4, "dateFormat", "yyyy-MM-dd", "decimalComma", true))
                .execute().errors().verify().path("bankFormatSave.id").entity(String.class).get();
    }

    private String upload(final String content) {
        return graphQl(userToken(TREASURER)).document("""
                        mutation($f: ID!, $content: String!) { importStatement(formatId: $f, fileName: "nov.csv", content: $content) { read added duplicates outgoing matched errors } }""")
                .variable("f", format).variable("content", content).execute().errors().verify().path("importStatement").entity(Map.class).get().toString();
    }

    @Test
    void aTransactionWithTheReferenceIsMatchedAtOnceAndANewUploadAddsNothing() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));
        final String file = "Datum;Belopp;Text;Namn\n" + YEAR + "-06-02;200,00;Swish " + referenceOf(request) + ";GRETA TEST\n" + YEAR + "-06-03;-50,00;Avgift;\n";

        assertThat(upload(file)).contains("added=1").contains("matched=1").contains("outgoing=1");
        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("SELECT how FROM payment").query(String.class).single()).isEqualTo("SWISH");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM payment WHERE transaction_pk IS NOT NULL").query(Long.class).single()).isEqualTo(1);

        assertThat(upload(file)).contains("added=0").contains("duplicates=1").contains("matched=0");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM payment").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aTransactionWithoutAReferenceIsSuggestedByNameAndAmountAndMatchedByHand() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));

        assertThat(upload("Datum;Belopp;Text;Namn\n" + YEAR + "-06-02;200,00;Medlemsavgift;GRETA TEST\n")).contains("added=1").contains("matched=0");

        final String transaction = graphQl(userToken(TREASURER)).document("{ unmatchedTransactions { id amount payer } }").execute().errors().verify()
                .path("unmatchedTransactions[0].id").entity(String.class).get();

        graphQl(userToken(TREASURER)).document("query($id: ID!) { transactionSuggestions(id: $id) { requestId why spexareName } }").variable("id", transaction)
                .execute().errors().verify().path("transactionSuggestions[0].requestId").entity(String.class).isEqualTo(request)
                .path("transactionSuggestions[0].why").entity(String.class).isEqualTo("NAME");

        graphQl(userToken(TREASURER)).document("mutation($id: ID!, $r: [ID!]!) { matchTransaction(id: $id, requestIds: $r) { status } }")
                .variable("id", transaction).variable("r", java.util.List.of(request)).execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).single()).isEqualTo("PAID");
        graphQl(userToken(TREASURER)).document("{ unmatchedTransactions { id } }").execute().errors().verify().path("unmatchedTransactions").entityList(Object.class).hasSize(0);
    }

    @Test
    void aMistypedReferenceIsSuggestedAndATransactionThatIsNotAMembershipPaymentIsDismissed() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));
        final String reference = referenceOf(request);
        final char wrong = reference.charAt(8) == 'A' ? 'B' : 'A';

        upload("Datum;Belopp;Text;Namn\n" + YEAR + "-06-02;50,00;" + reference.substring(0, 8) + wrong + ";Någon\n" + YEAR + "-06-03;99,00;Privat;Anna\n");

        final var transactions = graphQl(userToken(TREASURER)).document("{ unmatchedTransactions { id payer } }").execute().errors().verify();
        final String mistyped = transactions.path("unmatchedTransactions[?(@.payer == 'Någon')].id").entityList(String.class).get().getFirst();
        final String privateOne = transactions.path("unmatchedTransactions[?(@.payer == 'Anna')].id").entityList(String.class).get().getFirst();

        graphQl(userToken(TREASURER)).document("query($id: ID!) { transactionSuggestions(id: $id) { requestId why } }").variable("id", mistyped)
                .execute().errors().verify().path("transactionSuggestions[0].why").entity(String.class).isEqualTo("REFERENCE");
        graphQl(userToken(TREASURER)).document("mutation($id: ID!) { dismissTransaction(id: $id) { status } }").variable("id", privateOne).execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT text FROM bank_transaction WHERE status = 'DISMISSED'").query(String.class).optional()).isEmpty();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM bank_transaction WHERE status = 'DISMISSED' AND payer IS NULL").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFileWithAnUnreadableRowImportsNothing() {
        assertThat(upload("Datum;Belopp;Text;Namn\nigår;200;x;y\n")).contains("added=0").contains("Row 2");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM bank_transaction").query(Long.class).single()).isZero();
    }

    @Test
    void onlyTheTreasurerImportsAndOnlyAnAdminDescribesTheBanksFormat() {
        graphQl(userToken(BOARD)).document("{ bankFormats { id } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(TREASURER)).document("mutation { bankFormatDelete(id: \"x\") }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }
}
