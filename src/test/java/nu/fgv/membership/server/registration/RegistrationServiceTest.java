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

import nu.fgv.membership.server.FakeKeycloakAdmin;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.AccessMails;
import nu.fgv.membership.server.mail.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

    private final FakeKeycloakAdmin keycloak = new FakeKeycloakAdmin();
    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final AccessMails accessMails = mock(AccessMails.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        final MembershipProperties properties = new MembershipProperties("https://medlem.example",
                new MembershipProperties.Keycloak("http://kc", "fgv", "svc", "secret", "pending-approval", Duration.ofSeconds(5)), null,
                new MembershipProperties.Registration(Duration.ofHours(48), Duration.ofDays(7), List.of("admin@fgv.nu"), 10, 3), new MembershipProperties.Mail("noreply@fgv.nu"), List.of(), null);

        final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();

        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        service = new RegistrationService(keycloak, repository, new Mailer(sender, messages, properties), accessMails, properties, clock);
    }

    @Test
    void registers_a_pending_user_and_sends_a_verification_link() {
        service.register(new RegistrationRequest("Ada", "Lovelace", " Ada@Example.com ", "I sang in 1998", "en", "t"));

        final KeycloakUser user = keycloak.findUserByEmail("ada@example.com").orElseThrow();
        final ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        final ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);

        assertThat(keycloak.isMemberOfGroup(user.id(), "pending-approval")).isTrue();
        assertThat(user.emailVerified()).isFalse();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMessage()).isEqualTo("I sang in 1998");
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("ada@example.com");
        assertThat(mail.getValue().getSubject()).isEqualTo("Confirm your email address");
        assertThat(mail.getValue().getText()).contains("https://medlem.example/register/verify?token=").contains("48 hours");
    }

    @Test
    void does_not_reveal_that_an_address_has_an_account() {
        final KeycloakUser existing = keycloak.addUser("ada@example.com", false, true);

        service.register(new RegistrationRequest("Ada", "Lovelace", "ada@example.com", null, "sv", "t"));

        assertThat(keycloak.users).hasSize(1).containsKey(existing.id());
        verify(sender).send(any(SimpleMailMessage.class));
        verify(repository, never()).save(any());
    }

    @Test
    void removes_the_user_again_when_the_verification_email_cannot_be_sent() {
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("down")).when(sender).send(any(SimpleMailMessage.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.register(new RegistrationRequest("Ada", "Lovelace", "ada@example.com", null, "sv", "t"))).isInstanceOf(RuntimeException.class);

        assertThat(keycloak.users).isEmpty();
    }

    @Test
    void verifying_marks_the_address_verified_sends_set_password_and_tells_the_administrators() {
        final KeycloakUser user = keycloak.addUser("ada@example.com", true, false);
        final Registration registration = new Registration(user.id(), RegistrationService.hash("secret-token"), clock.instant().plusSeconds(60), "hello");

        when(repository.findByTokenHash(RegistrationService.hash("secret-token"))).thenReturn(Optional.of(registration));

        assertThat(service.verify("secret-token")).isTrue();
        assertThat(keycloak.users.get(user.id()).emailVerified()).isTrue();
        assertThat(keycloak.passwordEmails).containsExactly(user.id());
        assertThat(registration.isVerified()).isTrue();
        verify(accessMails).newUser(eq(List.of("admin@fgv.nu")), any(), eq("hello"), eq("https://medlem.example/admin/users/" + user.id()));
        // the link works once
        assertThat(service.verify("secret-token")).isFalse();
    }

    @Test
    void an_expired_or_unknown_token_is_refused() {
        final KeycloakUser user = keycloak.addUser("ada@example.com", true, false);

        when(repository.findByTokenHash(RegistrationService.hash("old"))).thenReturn(
                Optional.of(new Registration(user.id(), RegistrationService.hash("old"), clock.instant().minusSeconds(1), null)));

        assertThat(service.verify("old")).isFalse();
        assertThat(service.verify("unknown")).isFalse();
        assertThat(service.verify(" ")).isFalse();
        assertThat(keycloak.users.get(user.id()).emailVerified()).isFalse();
    }

    @Test
    void removes_users_who_never_verified() {
        final KeycloakUser stale = keycloak.addUser("stale@example.com", true, false);
        final KeycloakUser verified = keycloak.addUser("ok@example.com", false, true);

        when(repository.findByVerifiedAtIsNullAndCreatedAtBefore(any())).thenReturn(List.of(
                new Registration(stale.id(), "h1", clock.instant(), null), new Registration(verified.id(), "h2", clock.instant(), null)));

        assertThat(service.removeUnverified()).isEqualTo(1);
        assertThat(keycloak.users).doesNotContainKey(stale.id()).containsKey(verified.id());
    }
}
