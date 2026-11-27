package nu.fgv.membership.server;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registering an account here (Keycloak's own registration is off): the account is made in Keycloak, in the group of
 * those who wait for approval, the address is verified through an emailed link, and the administrators are told.
 */
class RegistrationIntegrationTest extends AbstractIntegrationTest {

    // Keycloak keeps its users between tests, so each test registers an address of its own
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger();
    private final String EMAIL = "ny" + COUNTER.incrementAndGet() + "@membership.test";
    private static final Pattern TOKEN = Pattern.compile("verify\\?token=([A-Za-z0-9_-]+)");

    private void register(final String email) {
        http().post().uri("/api/registration").header("Content-Type", "application/json")
                .bodyValue("{\"firstName\":\"Ny\",\"lastName\":\"Användare\",\"email\":\"" + email + "\",\"message\":\"Spexade 1998\",\"language\":\"sv\"}")
                .exchange().expectStatus().isAccepted();
    }

    @Test
    void anAccountIsMadeUnverifiedAndPendingAndAVerificationLinkIsMailed() {
        register(EMAIL);

        assertThat(userExists(EMAIL)).isTrue();
        assertThat(inGroup(EMAIL, "pending-approval")).isTrue();

        assertThat(body(mailTo(EMAIL))).contains("https://membership.test/register/verify?token=");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM registration").query(Long.class).single()).isEqualTo(1);
        // Only the hash of the token is stored
        assertThat(jdbcClient.sql("SELECT token_hash FROM registration").query(String.class).single()).hasSize(64);
    }

    @Test
    void theLinkVerifiesTheAddressOnceTellsTheAdministratorsAndSendsTheUserToChooseAPassword() {
        register(EMAIL);

        final Matcher matcher = TOKEN.matcher(body(mailWhere(message -> body(message).contains("verify?token="))));

        assertThat(matcher.find()).isTrue();

        final String token = matcher.group(1);

        http().post().uri("/api/registration/verify").header("Content-Type", "application/json").bodyValue("{\"token\":\"" + token + "\"}")
                .exchange().expectStatus().isOk();

        // The administrators' notice, and Keycloak's own mail with the link to choose a password
        final var notice = mailTo("admin@membership.test");

        assertThat(body(notice)).contains(EMAIL).contains("Spexade 1998");

        final var passwordMail = mailWhere(message -> recipients(message).stream().anyMatch(r -> r.contains(EMAIL)) && !body(message).contains("verify?token="));

        assertThat(body(passwordMail)).contains("http");

        http().post().uri("/api/registration/verify").header("Content-Type", "application/json").bodyValue("{\"token\":\"" + token + "\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void anAddressThatHasAnAccountGetsTheSameAnswerAndAMailAboutIt() {
        register(MEMBER);

        assertThat(body(mailTo(MEMBER))).contains("https://membership.test");
        assertThat(inGroup(MEMBER, "pending-approval")).isFalse();
    }

    @Test
    void aTokenThatDoesNotExistIsRefused() {
        http().post().uri("/api/registration/verify").header("Content-Type", "application/json").bodyValue("{\"token\":\"nope\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void anInvalidRegistrationIsRefused() {
        http().post().uri("/api/registration").header("Content-Type", "application/json")
                .bodyValue("{\"firstName\":\"\",\"lastName\":\"x\",\"email\":\"not-an-email\"}").exchange().expectStatus().isBadRequest();
    }
}
