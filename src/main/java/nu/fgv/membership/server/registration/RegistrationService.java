/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.membership.server.registration;

import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.AccessMails;
import nu.fgv.membership.server.mail.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Registering an account. The user is created in Keycloak, in the group of users who wait for approval, with no
 * password; they get a link by email that verifies the address and sends them on to set a password. Medlemskap never sees
 * a password.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final int SET_PASSWORD_LIFESPAN = (int) Duration.ofDays(7).toSeconds();

    private final SecureRandom random = new SecureRandom();
    private final KeycloakAdmin keycloak;
    private final RegistrationRepository registrations;
    private final Mailer mailer;
    private final AccessMails accessMails;
    private final MembershipProperties properties;
    private final Clock clock;
    private final nu.fgv.membership.server.payment.PaymentSettings settings;

    public RegistrationService(final KeycloakAdmin keycloak, final RegistrationRepository registrations, final Mailer mailer,
                               final AccessMails accessMails, final MembershipProperties properties, final Clock clock,
                               final nu.fgv.membership.server.payment.PaymentSettings settings) {
        this.settings = settings;
        this.keycloak = keycloak;
        this.registrations = registrations;
        this.mailer = mailer;
        this.accessMails = accessMails;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Registers a user. Whether the address already has an account is not revealed: the caller gets the same answer, and
     * the account holder gets an email.
     */
    @Transactional
    public void register(final RegistrationRequest request) {
        final String email = request.email().strip().toLowerCase(Locale.ROOT);
        final String language = request.language() == null ? "sv" : Mailer.localeOf(request.language()).getLanguage();
        final Optional<KeycloakUser> existing = keycloak.findUserByEmail(email);

        if (existing.isPresent()) {
            mailer.send(email, mailer.text(Mailer.localeOf(existing.get().locale()), "mail.exists.subject"),
                    mailer.text(Mailer.localeOf(existing.get().locale()), "mail.exists.body", properties.webUrl()));
            return;
        }

        final String id = keycloak.createUser(email, request.firstName().strip(), request.lastName().strip(), language);

        try {
            keycloak.joinGroup(id, properties.keycloak().pendingGroup());
            sendVerification(id, email, language, request.message() == null ? null : request.message().strip(), null);
        } catch (final RuntimeException e) {
            log.error("Registration of a new user failed, removing user {}", id, e);
            keycloak.deleteUser(id);
            throw e;
        }
    }

    /**
     * Sends a new verification link to a user who has not verified their address.
     */
    @Transactional
    public void resendVerification(final String userId) {
        final KeycloakUser user = keycloak.findUser(userId).orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));

        if (user.emailVerified()) {
            throw new IllegalStateException("The email address is already verified");
        }

        final Registration existing = registrations.findFirstByKeycloakUserIdOrderByCreatedAtDesc(userId).orElse(null);

        sendVerification(userId, user.email(), user.locale(), existing == null ? null : existing.getMessage(), existing);
    }

    /**
     * Verifies the address the link was sent to, tells the administrators and sends the user on to set a password.
     *
     * @return whether the token was valid
     */
    @Transactional
    public boolean verify(final String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        final Registration registration = registrations.findByTokenHash(hash(token)).orElse(null);

        if (registration == null || registration.isVerified() || registration.getExpiresAt().isBefore(clock.instant())) {
            return false;
        }

        final Optional<KeycloakUser> user = keycloak.findUser(registration.getKeycloakUserId());

        if (user.isEmpty()) {
            return false;
        }

        registration.markVerified();
        keycloak.setEmailVerified(user.get().id(), true);
        keycloak.sendSetPasswordEmail(user.get().id(), "fgv-membership", null, SET_PASSWORD_LIFESPAN);
        if (!settings.notifyRecipients().isEmpty()) {
            accessMails.newUser(settings.notifyRecipients(), user.get(), registration.getMessage(),
                    properties.webUrl() + "/admin/users/" + user.get().id());
        }
        return true;
    }

    /**
     * Removes accounts that were registered but never verified, and registrations that are old enough to forget.
     *
     * @return the number of accounts removed
     */
    @Transactional
    public int removeUnverified() {
        int removed = 0;

        for (final Registration registration : registrations.findByVerifiedAtIsNullAndCreatedAtBefore(clock.instant().minus(properties.registration().unverifiedExpiry()))) {
            final Optional<KeycloakUser> user = keycloak.findUser(registration.getKeycloakUserId());

            if (user.isPresent() && !user.get().emailVerified()) {
                keycloak.deleteUser(user.get().id());
                removed++;
            }
            registrations.delete(registration);
        }
        registrations.deleteByCreatedAtBefore(clock.instant().minus(Duration.ofDays(366)));
        return removed;
    }

    private void sendVerification(final String userId, final String email, final String language, final String message, final Registration existing) {
        final byte[] bytes = new byte[32];

        random.nextBytes(bytes);

        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        final var expires = clock.instant().plus(properties.registration().tokenValidity());

        if (existing == null) {
            registrations.save(new Registration(userId, hash(token), expires, message));
        } else {
            existing.renew(hash(token), expires);
        }

        final Locale locale = Mailer.localeOf(language);

        mailer.send(email, mailer.text(locale, "mail.verify.subject"),
                mailer.text(locale, "mail.verify.body", properties.webUrl() + "/register/verify?token=" + token,
                        properties.registration().tokenValidity().toHours()));
    }

    static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
