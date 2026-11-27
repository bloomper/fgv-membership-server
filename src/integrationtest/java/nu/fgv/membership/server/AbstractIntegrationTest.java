package nu.fgv.membership.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.mail.internet.MimeMessage;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JacksonJsonParser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * MySQL and Keycloak (with the committed copy of {@code fgv-keycloak}'s realm, plus fictitious users) in Testcontainers; a
 * real SMTP server (GreenMail) for the mail that Medlemskap and Keycloak send; WireMock for Spexregister. Every test starts
 * from empty tables, empty mailboxes and no stubs.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
public abstract class AbstractIntegrationTest {

    protected static final String DOMAIN = "@membership.test";
    protected static final String MEMBER = "medlem" + DOMAIN;
    protected static final String BOARD = "styrelse" + DOMAIN;
    protected static final String TREASURER = "kassor" + DOMAIN;
    protected static final String ADMIN = "admin" + DOMAIN;
    protected static final String PASSWORD = "s3cr3t-s3cr3t";
    protected static final String CLIENT_ID = "fgv-membership";
    protected static final String REALM = "fgv";
    protected static final String SPEXARE_ID = "0197d6a0-0000-7000-8000-000000000001";
    protected static final WireMockServer SPEXREGISTER = new WireMockServer(options().dynamicPort());
    protected static final GreenMail MAIL = new GreenMail(new ServerSetup(freePort(), "0.0.0.0", ServerSetup.PROTOCOL_SMTP));
    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final JacksonJsonParser JSON = new JacksonJsonParser();
    private static final Map<String, String> SECRETS = clientSecrets();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    // Children before parents
    private static final List<String> TABLES = List.of("personal_link", "payment", "payment_request_membership", "payment_request", "bank_transaction",
            "bank_format", "reminder_email", "reminder_campaign", "reminder_opt_out", "audit_entry", "application", "external_payment",
            "default_access", "app_setting", "import_key", "membership_change", "membership", "sync_consumer", "fee_level", "registration", "access_change");

    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("mysqld", "--innodb-flush-log-at-trx-commit=0", "--innodb-doublewrite=0", "--skip-log-bin", "--performance-schema=OFF");
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8").withRealmImportFile("/realm/fgv.json");

    static {
        MAIL.start();
        SPEXREGISTER.start();
        Testcontainers.exposeHostPorts(MAIL.getSmtp().getPort());
        Startables.deepStart(MYSQL, KEYCLOAK).join();
        prepareKeycloak();
    }

    @LocalServerPort
    protected int localPort;
    @Autowired
    protected JdbcClient jdbcClient;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
        registry.add("membership.keycloak.url", KEYCLOAK::getAuthServerUrl);
        registry.add("membership.keycloak.service-client-secret", () -> SECRETS.get("fgv-membership-service"));
        registry.add("membership.spexregister.graphql-url", () -> SPEXREGISTER.baseUrl() + "/spexregister/graphql");
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> MAIL.getSmtp().getPort());
    }

    @BeforeEach
    void resetState() throws Exception {
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 0").update();
        TABLES.forEach(table -> jdbcClient.sql("DELETE FROM " + table).update());
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 1").update();
        SPEXREGISTER.resetAll();
        MAIL.purgeEmailFromAllMailboxes();
    }

    // Tokens ------------------------------------------------------------------------------------------------------------

    protected static String userToken(final String email) {
        return TOKENS.computeIfAbsent(email, key -> {
            final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

            form.add("grant_type", "password");
            form.add("client_id", CLIENT_ID);
            form.add("client_secret", SECRETS.get(CLIENT_ID));
            form.add("username", key);
            form.add("password", PASSWORD);
            form.add("scope", "openid");
            return "Bearer " + token(form);
        });
    }

    protected static String serviceToken(final String clientId) {
        return TOKENS.computeIfAbsent("service:" + clientId, key -> {
            final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", SECRETS.get(clientId));
            return "Bearer " + token(form);
        });
    }

    @SuppressWarnings("unchecked")
    private static String token(final MultiValueMap<String, String> form) {
        final Map<String, Object> response = WebClient.builder().build().post()
                .uri(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form)).retrieve().bodyToMono(Map.class).block();

        return String.valueOf(Objects.requireNonNull(response).get("access_token"));
    }

    protected HttpGraphQlTester graphQl(final String authorization) {
        final WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + localPort + "/api/graphql");

        return HttpGraphQlTester.create(authorization == null ? client.build() : client.defaultHeader(HttpHeaders.AUTHORIZATION, authorization).build());
    }

    protected WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + localPort).responseTimeout(java.time.Duration.ofSeconds(30)).build();
    }

    // Spexregister (WireMock) -------------------------------------------------------------------------------------------

    /**
     * Answers a GraphQL operation (named by a fragment of the query) with {@code data}.
     */
    protected static void spexregister(final String operation, final String data) {
        SPEXREGISTER.stubFor(post(urlEqualTo("/spexregister/graphql")).withRequestBody(containing(operation))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"data\":" + data + "}")));
    }

    protected static String person(final String id, final String first, final String last, final String email) {
        return "{\"id\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"email\":%s}".formatted(id, first, last, email == null ? "null" : "\"" + email + "\"");
    }

    // Keycloak ----------------------------------------------------------------------------------------------------------

    private static Map<String, String> clientSecrets() {
        try (InputStream stream = AbstractIntegrationTest.class.getResourceAsStream("/realm/fgv.json")) {
            final Map<String, Object> realm = JSON.parseMap(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
            final Map<String, String> secrets = new ConcurrentHashMap<>();

            for (final Object client : (List<?>) realm.get("clients")) {
                if (client instanceof Map<?, ?> map && map.get("secret") instanceof String secret) {
                    secrets.put(String.valueOf(map.get("clientId")), secret);
                }
            }
            return secrets;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void prepareKeycloak() {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final ClientRepresentation web = realm.clients().findByClientId(CLIENT_ID).getFirst();

            web.setDirectAccessGrantsEnabled(true);
            realm.clients().get(web.getId()).update(web);

            // Keycloak's own mail (the link to choose a password) goes to the same SMTP server
            final org.keycloak.representations.idm.RealmRepresentation representation = realm.toRepresentation();

            representation.setSmtpServer(Map.of("host", "host.testcontainers.internal", "port", String.valueOf(MAIL.getSmtp().getPort()),
                    "from", "login@membership.test", "auth", "false", "starttls", "false", "ssl", "false"));
            realm.update(representation);

            // USER on Medlemskap comes with the realm's default role, as for every approved user
            createUser(realm, MEMBER, "Greta", List.of());
            createUser(realm, BOARD, "Berit", List.of("BOARD"));
            createUser(realm, TREASURER, "Kalle", List.of("TREASURER"));
            createUser(realm, ADMIN, "Anna", List.of("ADMIN"));
        }
    }

    protected static String createUser(final String email, final String firstName, final List<String> roles) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            return createUser(admin.realm(REALM), email, firstName, roles);
        }
    }

    private static String createUser(final RealmResource realm, final String email, final String firstName, final List<String> roles) {
        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName("Test");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            final String path = response.getLocation().getPath();
            final String userId = path.substring(path.lastIndexOf('/') + 1);

            if (!roles.isEmpty()) {
                final ClientResource client = realm.clients().get(realm.clients().findByClientId(CLIENT_ID).getFirst().getId());
                final List<RoleRepresentation> representations = roles.stream().map(role -> client.roles().get(role).toRepresentation()).toList();

                realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId()).add(representations);
            }
            return userId;
        }
    }

    /**
     * The id of a user in Keycloak, by email address.
     */
    protected static String keycloakLogs() {
        return KEYCLOAK.getLogs();
    }

    protected static String userId(final String email) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            return admin.realm(REALM).users().searchByEmail(email, true).getFirst().getId();
        }
    }

    protected static boolean userExists(final String email) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            return !admin.realm(REALM).users().searchByEmail(email, true).isEmpty();
        }
    }

    /**
     * The effective client roles a user holds on a client, by user email.
     */
    protected static List<String> roles(final String email, final String clientId) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final List<UserRepresentation> users = realm.users().searchByEmail(email, true);

            if (users.isEmpty()) {
                return List.of();
            }

            final String clientUuid = realm.clients().findByClientId(clientId).getFirst().getId();

            return realm.users().get(users.getFirst().getId()).roles().clientLevel(clientUuid).listEffective().stream().map(RoleRepresentation::getName).sorted().toList();
        }
    }

    protected static boolean inGroup(final String email, final String group) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);

            return realm.users().get(userId(email)).groups().stream().anyMatch(g -> g.getName().equals(group));
        }
    }

    // Mail --------------------------------------------------------------------------------------------------------------

    /**
     * Waits for mail and returns every message received so far.
     */
    protected static List<MimeMessage> mail(final int expected) {
        MAIL.waitForIncomingEmail(15_000, expected);
        return List.of(MAIL.getReceivedMessages());
    }

    /**
     * Waits for a message that satisfies the condition: mail from an earlier test may still be on its way, so tests look
     * for their own message instead of counting.
     */
    protected static MimeMessage mailWhere(final java.util.function.Predicate<MimeMessage> condition) {
        final long until = System.currentTimeMillis() + 20_000;

        while (System.currentTimeMillis() < until) {
            for (final MimeMessage message : MAIL.getReceivedMessages()) {
                if (condition.test(message)) {
                    return message;
                }
            }
            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("No such mail arrived; there were " + MAIL.getReceivedMessages().length);
    }

    protected static MimeMessage mailTo(final String address) {
        return mailWhere(message -> recipients(message).stream().anyMatch(recipient -> recipient.contains(address)));
    }

    protected static String body(final MimeMessage message) {
        try {
            return text(message.getContent());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The text of a message, also when it has several parts (Keycloak sends plain text and HTML).
     */
    private static String text(final Object content) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            final StringBuilder text = new StringBuilder();

            for (int i = 0; i < multipart.getCount(); i++) {
                text.append(text(multipart.getBodyPart(i).getContent())).append('\n');
            }
            return text.toString();
        }
        return String.valueOf(content);
    }

    protected static String subject(final MimeMessage message) {
        try {
            return message.getSubject();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static List<String> recipients(final MimeMessage message) {
        try {
            return java.util.Arrays.stream(message.getAllRecipients()).map(Object::toString).toList();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
