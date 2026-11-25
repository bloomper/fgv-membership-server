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

package nu.fgv.membership.server.mail;

import nu.fgv.membership.server.access.ApplicationAccess;
import nu.fgv.membership.server.keycloak.KeycloakClient;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The emails around access that Keycloak's {@code fgv-admin-notification} used to send. A failure to send is logged and
 * does not undo the change that caused it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AccessMails {

    private static final Logger log = LoggerFactory.getLogger(AccessMails.class);

    private final Mailer mailer;

    public AccessMails(final Mailer mailer) {
        this.mailer = mailer;
    }

    /**
     * An administrator approved a pending user by giving them access.
     */
    public void approved(final KeycloakUser user, final List<ApplicationAccess> applications) {
        final Locale locale = Mailer.localeOf(user.locale());
        final List<ApplicationAccess> usable = applications.stream().filter(a -> a.url() != null).toList();
        final String body = usable.isEmpty()
                ? mailer.text(locale, "mail.approved.none")
                : mailer.text(locale, "mail.approved.list", usable.stream()
                .map(a -> "- %s: %s".formatted(a.name(), a.url())).collect(Collectors.joining("\n")));

        send(user, mailer.text(locale, "mail.approved.subject"), body);
    }

    /**
     * An approved user was given access to a further application.
     */
    public void firstAccess(final KeycloakUser user, final KeycloakClient client) {
        final Locale locale = Mailer.localeOf(user.locale());

        send(user, mailer.text(locale, "mail.access.subject", client.displayName()),
                mailer.text(locale, "mail.access.body", client.displayName(), client.baseUrl()));
    }

    public void rejected(final KeycloakUser user, final String message) {
        final Locale locale = Mailer.localeOf(user.locale());
        final String extra = message == null || message.isBlank() ? "" : mailer.text(locale, "mail.rejected.message", message.strip());

        send(user, mailer.text(locale, "mail.rejected.subject"), mailer.text(locale, "mail.rejected.body", extra));
    }

    /**
     * A new user has verified their email address and waits for approval.
     */
    public void newUser(final List<String> recipients, final KeycloakUser user, final String message, final String adminUrl) {
        final Locale locale = Locale.of("sv");
        final String text = message == null || message.isBlank() ? "" : mailer.text(locale, "mail.newuser.message", message.strip());

        recipients.forEach(recipient -> {
            try {
                mailer.send(recipient, mailer.text(locale, "mail.newuser.subject"),
                        mailer.text(locale, "mail.newuser.body", user.displayName(), user.email(), text, adminUrl));
            } catch (final MailException e) {
                log.error("Could not tell {} about the new user {}", recipient, user.id(), e);
            }
        });
    }

    private void send(final KeycloakUser user, final String subject, final String body) {
        if (user.email() == null || user.email().isBlank()) {
            log.warn("User {} has no email address, could not send \"{}\"", user.id(), subject);
            return;
        }
        try {
            mailer.send(user.email(), subject, body);
        } catch (final MailException e) {
            log.error("Could not send \"{}\" to user {}", subject, user.id(), e);
        }
    }
}
