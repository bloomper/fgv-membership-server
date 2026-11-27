package nu.fgv.membership.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nyuppsättning paying for its participants, applications for membership, and administration: settings, the maintenance
 * jobs and the audit log.
 */
class RevivalApplicationAndAdminIntegrationTest extends AbstractPaymentIntegrationTest {

    private static final String B = "0197d6a0-0000-7000-8000-000000000002";

    @BeforeEach
    void knownMember() {
        spexregisterKnowsTheMember();
    }

    @Test
    void aRevivalPaysForItsParticipantsWithOneRequestAndTheMembershipsArePaidByTheRevival() {
        final String fee = createFee("STANDARD", YEAR, 200);

        graphQl(serviceToken("fgv-revival-service")).document("query($y: Int) { revivalFeeLevels(year: $y) { id amount kind } }").variable("y", YEAR).execute().errors().verify()
                .path("revivalFeeLevels[0].id").entity(String.class).isEqualTo(fee);

        final String request = graphQl(serviceToken("fgv-revival-service")).document("""
                        mutation($ids: [ID!]!, $fee: ID!) { createRevivalPaymentRequest(payerLabel: "Nyuppsättning 2027", spexareIds: $ids, feeLevelId: $fee,
                        key: "revival-7-2027", actingPerson: "Dirk Ektör") { requestId reference amount included existing status alreadyMembers payee { bankgiro swishNumber } } }""")
                .variable("ids", List.of(SPEXARE_ID, B)).variable("fee", fee).execute().errors().verify()
                .path("createRevivalPaymentRequest.amount").entity(Integer.class).isEqualTo(400)
                .path("createRevivalPaymentRequest.included").entity(Integer.class).isEqualTo(2)
                .path("createRevivalPaymentRequest.payee.bankgiro").entity(String.class).isEqualTo("123-4567")
                .path("createRevivalPaymentRequest.requestId").entity(String.class).get();

        // Asking again for the same key gives the same request, even for more people
        graphQl(serviceToken("fgv-revival-service")).document("""
                        mutation($ids: [ID!]!, $fee: ID!) { createRevivalPaymentRequest(payerLabel: "Nyuppsättning 2027", spexareIds: $ids, feeLevelId: $fee, key: "revival-7-2027")
                        { requestId existing included } }""")
                .variable("ids", List.of(SPEXARE_ID, B, "0197d6a0-0000-7000-8000-000000000003")).variable("fee", fee).execute().errors().verify()
                .path("createRevivalPaymentRequest.requestId").entity(String.class).isEqualTo(request)
                .path("createRevivalPaymentRequest.existing").entity(Boolean.class).isEqualTo(true)
                .path("createRevivalPaymentRequest.included").entity(Integer.class).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM payment_request").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT detail FROM audit_entry WHERE action = 'REVIVAL_PAYMENT_REQUEST'").query(String.class).single()).contains("Dirk Ektör").contains("2 people");

        graphQl(serviceToken("fgv-revival-service")).document("query($id: ID!) { revivalPaymentRequest(id: $id) { status paid amount } }").variable("id", request).execute().errors().verify()
                .path("revivalPaymentRequest.status").entity(String.class).isEqualTo("OPEN");
        graphQl(userToken(TREASURER)).document("{ waitingPaymentRequests { id spexareName spexareId } }").execute().errors().verify()
                .path("waitingPaymentRequests[0].spexareName").entity(String.class).isEqualTo("Nyuppsättning 2027");
        record(request, 400);
        graphQl(serviceToken("fgv-revival-service")).document("query($id: ID!) { revivalPaymentRequest(id: $id) { status paid } }").variable("id", request).execute().errors().verify()
                .path("revivalPaymentRequest.status").entity(String.class).isEqualTo("MATCHED")
                .path("revivalPaymentRequest.paid").entity(Integer.class).isEqualTo(400);

        assertThat(jdbcClient.sql("SELECT how FROM membership").query(String.class).list()).containsOnly("REVIVAL");
        assertThat(jdbcClient.sql("SELECT status FROM membership").query(String.class).list()).containsOnly("PAID");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM membership_change").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theRevivalCannotSeeMembersOrPaymentsOrAskForAFamilyFee() {
        final String family = createFee("FAMILY", YEAR, 350);

        graphQl(serviceToken("fgv-revival-service")).document("query($y: Int!) { members(year: $y) { name } }").variable("y", YEAR).execute().errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(serviceToken("fgv-revival-service")).document("mutation($ids: [ID!]!, $fee: ID!) { createRevivalPaymentRequest(payerLabel: \"X\", spexareIds: $ids, feeLevelId: $fee) { requestId } }")
                .variable("ids", List.of(SPEXARE_ID)).variable("fee", family).execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void anApplicationIsAcceptedByLinkingTheAccountAndTheApplicantIsTold() {
        final String applicant = "sokande@membership.test";

        createUser(applicant, "Sökande", List.of());
        spexregister("medlemskapSpexareForUser", "{\"medlemskapSpexareForUser\":null}");
        spexregister("medlemskapLinkUser", "{\"medlemskapLinkUser\":" + person(SPEXARE_ID, "Greta", "Test", applicant) + "}");

        final String application = graphQl(userToken(applicant)).document("mutation { applyForMembership(name: \"Sökande Test\", text: \"Spexade 1999\") { id status } }")
                .execute().errors().verify().path("applyForMembership.id").entity(String.class).get();

        graphQl(userToken(applicant)).document("mutation { applyForMembership(name: \"Igen\", text: \"Igen\") { id } }").execute().errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(BOARD)).document("{ membershipApplications { id applicantName applicantEmail text } }").execute().errors().verify()
                .path("membershipApplications[0].applicantEmail").entity(String.class).isEqualTo(applicant);

        graphQl(userToken(BOARD)).document("mutation($id: ID!, $s: ID) { acceptApplication(id: $id, spexareId: $s) { status } }").variable("id", application).variable("s", SPEXARE_ID)
                .execute().errors().verify().path("acceptApplication.status").entity(String.class).isEqualTo("ACCEPTED");

        assertThat(body(mailTo(applicant))).contains("https://membership.test/membership");
        assertThat(jdbcClient.sql("SELECT spexare_id FROM application").query(String.class).single()).isEqualTo(SPEXARE_ID);
        graphQl(userToken(BOARD)).document("{ membershipApplications { id } }").execute().errors().verify().path("membershipApplications").entityList(Object.class).hasSize(0);
    }

    @Test
    void anApplicationCanBeRejectedWithAMessage() {
        final String applicant = "avvisad@membership.test";

        createUser(applicant, "Avvisad", List.of());
        spexregister("medlemskapSpexareForUser", "{\"medlemskapSpexareForUser\":null}");

        final String application = graphQl(userToken(applicant)).document("mutation { applyForMembership(name: \"Av Visad\", text: \"Nej\") { id } }")
                .execute().errors().verify().path("applyForMembership.id").entity(String.class).get();

        graphQl(userToken(BOARD)).document("mutation($id: ID!) { rejectApplication(id: $id, message: \"Vi känner inte igen dig\") { status } }").variable("id", application)
                .execute().errors().verify();
        assertThat(body(mailTo(applicant))).contains("Vi känner inte igen dig");
    }

    @Test
    void settingsAreSavedAndWinOverTheConfigurationAndTheAuditLogSaysWho() {
        graphQl(userToken(ADMIN)).document("{ settings { swishNumber bankgiro bankAccount reminderDays recipients } }").execute().errors().verify()
                .path("settings.bankgiro").entity(String.class).isEqualTo("123-4567")
                .path("settings.recipients[0]").entity(String.class).isEqualTo("admin@membership.test");

        graphQl(userToken(ADMIN)).document("mutation($i: SettingsInput!) { saveSettings(input: $i) { bankgiro reminderDays recipients } }")
                .variable("i", Map.of("swishNumber", "070 999 99 99", "bankgiro", "555-5555", "bankAccount", "", "payeeName", "FGV", "reminderDays", 5, "recipients", "a@fgv.test\nb@fgv.test"))
                .execute().errors().verify().path("saveSettings.recipients").entityList(String.class).containsExactly("a@fgv.test", "b@fgv.test");

        // A new payment page shows the new bankgiro, and a new notice goes to the new recipients
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));

        graphQl(userToken(MEMBER)).document("query($id: ID!) { paymentRequest(id: $id) { payee { bankgiro swishNumber } } }").variable("id", request)
                .execute().errors().verify().path("paymentRequest.payee.bankgiro").entity(String.class).isEqualTo("555-5555");
        graphQl(userToken(MEMBER)).document("mutation($id: ID!) { reportPaid(id: $id) { status } }").variable("id", request).execute().errors().verify();
        mailTo("a@fgv.test");
        mailTo("b@fgv.test");

        graphQl(userToken(ADMIN)).document("{ auditLog { action actorName detail } }").execute().errors().verify()
                .path("auditLog[?(@.action == 'SETTINGS_SAVED')].actorName").entityList(String.class).contains("Anna Test");
        graphQl(userToken(MEMBER)).document("{ auditLog { action } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void theAuditLogAlsoShowsChangesToUsersAccess() {
        final String id = createUser("granskad@membership.test", "Granskad", List.of());

        graphQl(userToken(ADMIN)).document("mutation($u: ID!) { setApplicationAccess(userId: $u, clientId: \"fgv-spexify\", role: \"EDITOR\", notify: false) { highest } }").variable("u", id)
                .execute().errors().verify();
        graphQl(userToken(ADMIN)).document("{ auditLog { action subject detail } }").execute().errors().verify()
                .path("auditLog[?(@.action == 'ACCESS_ACCESS')].subject").entityList(String.class).contains(id);
    }

    @Test
    void theMaintenanceJobsRunOnDemandAndSayWhatTheyDid() {
        graphQl(userToken(ADMIN)).document("{ maintenanceJobs { name lastRun } }").execute().errors().verify()
                .path("maintenanceJobs").entityList(Object.class).satisfies(jobs -> assertThat(jobs).hasSizeGreaterThanOrEqualTo(5));
        graphQl(userToken(ADMIN)).document("mutation { runMaintenanceJob(name: \"PAYMENT_REMINDERS\") { name lastRun lastResult } }").execute().errors().verify()
                .path("runMaintenanceJob.lastResult").entity(String.class).isEqualTo("0 handled");
        graphQl(userToken(ADMIN)).document("mutation { runMaintenanceJob(name: \"NOPE\") { name } }").execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void anOpenRequestIsRemindedOnceByTheJobWhenItIsOldEnough() {
        final String request = requestPayment(createFee("STANDARD", YEAR, 200));

        jdbcClient.sql("UPDATE payment_request SET created_at = DATE_SUB(NOW(), INTERVAL 5 DAY)").update();
        graphQl(userToken(ADMIN)).document("mutation { runMaintenanceJob(name: \"PAYMENT_REMINDERS\") { lastResult } }").execute().errors().verify()
                .path("runMaintenanceJob.lastResult").entity(String.class).isEqualTo("1 handled");
        assertThat(body(mailWhere(message -> subject(message).contains("Påminnelse")))).contains(referenceOf(request));
        graphQl(userToken(ADMIN)).document("mutation { runMaintenanceJob(name: \"PAYMENT_REMINDERS\") { lastResult } }").execute().errors().verify()
                .path("runMaintenanceJob.lastResult").entity(String.class).isEqualTo("0 handled");
    }
}
