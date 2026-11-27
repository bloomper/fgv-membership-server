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

package nu.fgv.membership.server.application;

import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.Mailer;
import nu.fgv.membership.server.membership.MembershipRepository;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Applying for membership (spec 5.6). A user whose account is not linked to a spexare, or who has never been an FGV
 * member, says who they are and how they have been part of the spex. The board finds the spexare in Spexregister and
 * links the account, or has the spexare created there, and accepts; the user is told by email and can pay. The board can
 * also reject with a message. Decided applications are kept for 12 months.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    static final Duration KEPT = Duration.ofDays(365);

    private final ApplicationRepository applications;
    private final KeycloakAdmin keycloak;
    private final SpexregisterClient spexregister;
    private final MembershipRepository memberships;
    private final Mailer mailer;
    private final Clock clock;
    private final String webUrl;

    @SuppressWarnings("java:S107")
    public ApplicationService(final ApplicationRepository applications, final KeycloakAdmin keycloak, final SpexregisterClient spexregister,
                              final MembershipRepository memberships, final Mailer mailer, final Clock clock,
                              @Value("${membership.web-url}") final String webUrl) {
        this.applications = applications;
        this.keycloak = keycloak;
        this.spexregister = spexregister;
        this.memberships = memberships;
        this.mailer = mailer;
        this.clock = clock;
        this.webUrl = webUrl;
    }

    /**
     * @param applicantEmail from the account in Keycloak, for the board to see
     */
    public record Item(String id, String applicantName, String applicantEmail, String text, Application.Status status,
                       String createdAt,
                       String decisionMessage) {
    }

    @Transactional
    public Item apply(final String userId, final String name, final String text) {
        if (name == null || name.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Say who you are and how you have been part of the spex");
        }
        if (text.length() > 1000 || name.length() > 200) {
            throw new IllegalArgumentException("That is too long");
        }

        final Optional<Spexare> spexare = spexregister.forUser(userId);

        if (spexare.isPresent() && memberships.findBySpexareId(spexare.get().id()).stream().anyMatch(m -> m.counts() || m.getStatus().name().equals("PENDING"))) {
            throw new IllegalStateException("You are a member already, or have asked to pay: there is no need to apply");
        }
        if (applications.findFirstByUserIdOrderByCreatedAtDesc(userId).filter(a -> a.getStatus() == Application.Status.PENDING).isPresent()) {
            throw new IllegalStateException("You have applied already; the board will answer");
        }
        return item(applications.save(new Application(userId, name.strip(), text.strip(), clock.instant())), null);
    }

    @Transactional(readOnly = true)
    public Optional<Item> mine(final String userId) {
        return applications.findFirstByUserIdOrderByCreatedAtDesc(userId).map(application -> item(application, null));
    }

    @Transactional(readOnly = true)
    public List<Item> pending() {
        return applications.findByStatusOrderByCreatedAtAsc(Application.Status.PENDING).stream()
                .map(application -> item(application, keycloak.findUser(application.getUserId()).map(KeycloakUser::email).orElse(null))).toList();
    }

    /**
     * Accepts the application: the account is linked to the spexare, which is the one given or, when none is, one that
     * is created in Spexregister from the account's name and email address (or found there by that address).
     *
     * @param spexareId the spexare the board found in Spexregister, or null to create one
     */
    @Transactional
    public Item accept(final String actor, final String applicationId, final String spexareId) {
        final Application application = find(applicationId);

        if (application.getStatus() != Application.Status.PENDING) {
            throw new IllegalStateException("The application is " + application.getStatus());
        }

        final KeycloakUser user = keycloak.findUser(application.getUserId()).orElseThrow(() -> new IllegalStateException("The account is gone"));
        final String spexare = spexareId != null ? spexareId : spexregister.create(
                user.firstName() == null || user.firstName().isBlank() ? application.getApplicantName() : user.firstName(),
                user.lastName() == null || user.lastName().isBlank() ? "-" : user.lastName(), user.email()).id();

        spexregister.link(application.getUserId(), spexare);
        application.accept(actor, spexare, clock.instant());
        send(user, "mail.application.accepted.subject", "mail.application.accepted.body", webUrl + "/membership");
        return item(application, user.email());
    }

    @Transactional
    public Item reject(final String actor, final String applicationId, final String message) {
        final Application application = find(applicationId);

        if (application.getStatus() != Application.Status.PENDING) {
            throw new IllegalStateException("The application is " + application.getStatus());
        }
        application.reject(actor, message == null || message.isBlank() ? null : message.strip(), clock.instant());

        final KeycloakUser user = keycloak.findUser(application.getUserId()).orElse(null);

        if (user != null) {
            send(user, "mail.application.rejected.subject", "mail.application.rejected.body",
                    application.getDecisionMessage() == null ? "" : mailer.text(Locale.of("sv"), "mail.application.message", application.getDecisionMessage()));
        }
        return item(application, user == null ? null : user.email());
    }

    /**
     * Removes applications decided more than 12 months ago.
     *
     * @return how many
     */
    @Transactional
    public int removeOld() {
        final List<Application> old = applications.findByStatusNotAndDecidedAtBefore(Application.Status.PENDING, clock.instant().minus(KEPT));

        applications.deleteAll(old);
        return old.size();
    }

    private void send(final KeycloakUser user, final String subject, final String body, final String argument) {
        if (user.email() == null || user.email().isBlank()) {
            return;
        }

        final Locale locale = Mailer.localeOf(user.locale());

        try {
            mailer.send(user.email(), mailer.text(locale, subject), mailer.text(locale, body, argument));
        } catch (final MailException e) {
            log.error("Could not tell {} about their application", user.id(), e);
        }
    }

    private Application find(final String id) {
        return applications.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown application"));
    }

    private static Item item(final Application a, final String email) {
        return new Item(a.getId(), a.getApplicantName(), email, a.getText(), a.getStatus(), a.getCreatedAt().toString(), a.getDecisionMessage());
    }
}
