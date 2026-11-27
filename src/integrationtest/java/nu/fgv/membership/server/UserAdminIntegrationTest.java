package nu.fgv.membership.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Administering users through Keycloak's admin API with the service account: roles go together as a ladder, access to a
 * first application approves a pending user, and an administrator cannot lock the administrators out.
 */
class UserAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String PENDING = "vantar@membership.test";

    private static final String SET_ACCESS = "mutation($user: ID!, $client: String!, $role: String, $notify: Boolean) "
            + "{ setApplicationAccess(userId: $user, clientId: $client, role: $role, notify: $notify) { highest held consistent } }";

    @Test
    void givingAdminOnSpexregisterAlsoGivesEditorAndUserAndTakingEditorAwayTakesAdminToo() {
        final String id = createUser("roller@membership.test", "Roller", List.of());
        final var admin = graphQl(userToken(ADMIN));

        admin.document(SET_ACCESS).variable("user", id).variable("client", "fgv-spexregister").variable("role", "ADMIN").variable("notify", false)
                .execute().errors().verify().path("setApplicationAccess.highest").entity(String.class).isEqualTo("ADMIN");
        assertThat(roles("roller@membership.test", "fgv-spexregister")).containsExactly("ADMIN", "EDITOR", "USER");

        admin.document(SET_ACCESS).variable("user", id).variable("client", "fgv-spexregister").variable("role", "USER").variable("notify", false)
                .execute().errors().verify();
        assertThat(roles("roller@membership.test", "fgv-spexregister")).containsExactly("USER");

        admin.document(SET_ACCESS).variable("user", id).variable("client", "fgv-spexregister").variable("role", null).variable("notify", false)
                .execute().errors().verify();
        assertThat(roles("roller@membership.test", "fgv-spexregister")).isEmpty();
    }

    @Test
    void aServiceRoleCannotBeGivenToAPerson() {
        final String id = createUser("service@membership.test", "Service", List.of());

        graphQl(userToken(ADMIN)).document(SET_ACCESS).variable("user", id).variable("client", "fgv-spexregister").variable("role", "NOTIFY").variable("notify", false)
                .execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        assertThat(roles("service@membership.test", "fgv-spexregister")).doesNotContain("NOTIFY");
    }

    @Test
    void anAdministratorCannotDemoteOrDeactivateThemselvesOrTheLastAdministrator() {
        final String me = userId(ADMIN);

        graphQl(userToken(ADMIN)).document(SET_ACCESS).variable("user", me).variable("client", "fgv-membership").variable("role", "BOARD").variable("notify", false)
                .execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(userToken(ADMIN)).document("mutation($id: ID!) { setUserEnabled(id: $id, enabled: false) { id } }").variable("id", me)
                .execute().errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        assertThat(roles(ADMIN, "fgv-membership")).contains("ADMIN");
    }

    @Test
    void deactivatingAUserSwitchesThemOffInKeycloak() {
        final String id = createUser("av@membership.test", "Av", List.of());

        graphQl(userToken(ADMIN)).document("mutation($id: ID!, $on: Boolean!) { setUserEnabled(id: $id, enabled: $on) { enabled status } }")
                .variable("id", id).variable("on", false).execute().errors().verify().path("setUserEnabled.status").entity(String.class).isEqualTo("DEACTIVATED");
        graphQl(userToken(ADMIN)).document("mutation($id: ID!, $on: Boolean!) { setUserEnabled(id: $id, enabled: $on) { enabled } }")
                .variable("id", id).variable("on", true).execute().errors().verify().path("setUserEnabled.enabled").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void theFirstAccessApprovesAPendingUserAndMailsThemOnlyWhenAsked() throws Exception {
        http().post().uri("/api/registration").header("Content-Type", "application/json")
                .bodyValue("{\"firstName\":\"Vän\",\"lastName\":\"Tar\",\"email\":\"" + PENDING + "\"}").exchange().expectStatus().isAccepted();
        mail(1);
        MAIL.purgeEmailFromAllMailboxes();

        final String id = userId(PENDING);

        assertThat(inGroup(PENDING, "pending-approval")).isTrue();
        graphQl(userToken(ADMIN)).document(SET_ACCESS).variable("user", id).variable("client", "fgv-spexregister").variable("role", "USER").variable("notify", false)
                .execute().errors().verify();
        assertThat(inGroup(PENDING, "pending-approval")).isFalse();
        assertThat(MAIL.getReceivedMessages()).isEmpty();

        // The next application, with notification
        graphQl(userToken(ADMIN)).document(SET_ACCESS).variable("user", id).variable("client", "fgv-spexipedia").variable("role", "USER").variable("notify", true)
                .execute().errors().verify();
        assertThat(body(mailTo(PENDING))).contains("fgv-spexipedia".substring(0, 0) + "http");
    }

    @Test
    void rejectingAPendingUserRemovesTheAccountAndMailsThemOnlyWhenAsked() throws Exception {
        http().post().uri("/api/registration").header("Content-Type", "application/json")
                .bodyValue("{\"firstName\":\"Av\",\"lastName\":\"Slag\",\"email\":\"avslag@membership.test\"}").exchange().expectStatus().isAccepted();
        mail(1);
        MAIL.purgeEmailFromAllMailboxes();

        graphQl(userToken(ADMIN)).document("mutation($id: ID!) { rejectUser(id: $id, message: \"Tyvärr\", notify: false) }").variable("id", userId("avslag@membership.test"))
                .execute().errors().verify();
        assertThat(userExists("avslag@membership.test")).isFalse();
        assertThat(MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    void listingUsersFindsThemByNameAndStatus() {
        graphQl(userToken(ADMIN)).document("{ users(search: \"Anna\") { total items { email status } } }").execute().errors().verify()
                .path("users.items[0].email").entity(String.class).isEqualTo(ADMIN);
        graphQl(userToken(ADMIN)).document("{ users(status: PENDING) { total } }").execute().errors().verify();
    }

    @Test
    void theAdministeredApplicationsAreTheOnesInTheRealm() {
        graphQl(userToken(ADMIN)).document("{ applications { clientId ladder } }").execute().errors().verify()
                .path("applications[?(@.clientId == 'fgv-membership')].ladder[*]").entityList(String.class).containsExactly("USER", "BOARD", "TREASURER", "ADMIN");
    }
}
