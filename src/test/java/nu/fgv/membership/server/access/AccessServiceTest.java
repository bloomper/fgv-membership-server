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

package nu.fgv.membership.server.access;

import nu.fgv.membership.server.FakeKeycloakAdmin;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.AccessMails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AccessServiceTest {

    private final FakeKeycloakAdmin keycloak = new FakeKeycloakAdmin()
            .withClient("fgv-membership", "Medlemskap", "https://medlem.example")
            .withClient("fgv-spexregister", "Spexregister", "https://register.example")
            .withClient("fgv-spexify", "Spexify", "https://spexify.example");
    private final AccessChangeRepository changes = mock(AccessChangeRepository.class);
    private final AccessMails mails = mock(AccessMails.class);
    private AccessService service;
    private KeycloakUser admin;

    @BeforeEach
    void setUp() {
        final MembershipProperties properties = new MembershipProperties("https://medlem.example",
                new MembershipProperties.Keycloak("http://kc", "fgv", "svc", "secret", "pending-approval", Duration.ofSeconds(5)), null, null, null,
                List.of(new MembershipProperties.Application("fgv-membership", List.of("USER", "BOARD", "TREASURER", "ADMIN"), true),
                        new MembershipProperties.Application("fgv-spexregister", List.of("USER", "EDITOR", "ADMIN"), false),
                        new MembershipProperties.Application("fgv-spexify", List.of("USER", "EDITOR", "ADMIN"), false)), null);

        service = new AccessService(keycloak, new ApplicationCatalog(properties, keycloak), changes, mails, properties);
        admin = keycloak.addUser("admin@fgv.nu", false, true);
        keycloak.giveRoles(admin.id(), "fgv-membership", "USER", "BOARD", "TREASURER", "ADMIN");
    }

    @Test
    void giving_admin_gives_editor_and_user_too() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        service.setAccess(admin.id(), user.id(), "fgv-spexregister", "ADMIN", true);

        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).containsExactlyInAnyOrder("USER", "EDITOR", "ADMIN");
    }

    @Test
    void taking_editor_takes_admin_too() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        keycloak.giveRoles(user.id(), "fgv-spexregister", "USER", "EDITOR", "ADMIN");
        service.setAccess(admin.id(), user.id(), "fgv-spexregister", "USER", true);

        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).containsExactly("USER");
    }

    @Test
    void removing_access_removes_all_roles_but_leaves_service_roles() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        keycloak.giveRoles(user.id(), "fgv-spexregister", "USER", "EDITOR", "NOTIFY");
        service.setAccess(admin.id(), user.id(), "fgv-spexregister", null, true);

        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).containsExactly("NOTIFY");
    }

    @Test
    void first_access_approves_a_pending_user_and_sends_one_email_listing_the_applications() {
        final KeycloakUser user = keycloak.addUser("new@fgv.nu", true, true);

        service.setAccess(admin.id(), user.id(), "fgv-spexregister", "USER", true);

        assertThat(keycloak.isMemberOfGroup(user.id(), "pending-approval")).isFalse();
        verify(mails).approved(any(), anyList());
        verify(mails, never()).firstAccess(any(), any());
    }

    @Test
    void access_to_a_further_application_sends_a_first_access_email() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        keycloak.giveRoles(user.id(), "fgv-spexregister", "USER");
        service.setAccess(admin.id(), user.id(), "fgv-spexify", "USER", true);

        verify(mails).firstAccess(any(), any());
    }

    @Test
    void another_role_on_an_application_already_used_sends_nothing() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        keycloak.giveRoles(user.id(), "fgv-spexregister", "USER");
        service.setAccess(admin.id(), user.id(), "fgv-spexregister", "EDITOR", true);

        verifyNoInteractions(mails);
    }

    @Test
    void an_unknown_role_is_refused() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        assertThatThrownBy(() -> service.setAccess(admin.id(), user.id(), "fgv-spexregister", "SYNC", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_administrator_cannot_demote_themselves() {
        keycloak.addUser("other@fgv.nu", false, true);

        assertThatThrownBy(() -> service.setAccess(admin.id(), admin.id(), "fgv-membership", "BOARD", true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_last_administrator_cannot_be_demoted_or_deactivated() {
        final KeycloakUser other = keycloak.addUser("other@fgv.nu", false, true);

        keycloak.giveRoles(other.id(), "fgv-membership", "USER", "BOARD", "TREASURER", "ADMIN");
        // the administrator removes the other administrator: fine
        service.setAccess(admin.id(), other.id(), "fgv-membership", "USER", true);
        // and now nobody may remove the last
        keycloak.giveRoles(other.id(), "fgv-membership", "USER", "BOARD", "TREASURER", "ADMIN");
        keycloak.users.remove(admin.id());
        assertThatThrownBy(() -> service.setAccess("someone", other.id(), "fgv-membership", "USER", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.setEnabled("someone", other.id(), false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deactivating_disables_and_ends_sessions() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        service.setEnabled(admin.id(), user.id(), false);

        assertThat(keycloak.users.get(user.id()).enabled()).isFalse();
        assertThat(keycloak.loggedOut).contains(user.id());
    }

    @Test
    void only_pending_users_can_be_rejected() {
        final KeycloakUser approved = keycloak.addUser("a@fgv.nu", false, true);
        final KeycloakUser pending = keycloak.addUser("p@fgv.nu", true, true);

        assertThatThrownBy(() -> service.reject(admin.id(), approved.id(), null, true)).isInstanceOf(IllegalStateException.class);
        service.reject(admin.id(), pending.id(), "No thanks", true);

        assertThat(keycloak.users).doesNotContainKey(pending.id());
        verify(mails).rejected(any(), org.mockito.ArgumentMatchers.eq("No thanks"));
    }

    @Test
    void an_update_without_notification_sends_no_email_but_still_approves() {
        final KeycloakUser user = keycloak.addUser("new@fgv.nu", true, true);

        service.setAccess(admin.id(), user.id(), "fgv-spexregister", "USER", false);

        assertThat(keycloak.isMemberOfGroup(user.id(), "pending-approval")).isFalse();
        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).contains("USER");
        verifyNoInteractions(mails);
    }

    @Test
    void a_further_application_without_notification_sends_no_first_access_email() {
        final KeycloakUser user = keycloak.addUser("a@fgv.nu", false, true);

        keycloak.giveRoles(user.id(), "fgv-spexregister", "USER");
        service.setAccess(admin.id(), user.id(), "fgv-spexify", "USER", false);

        verifyNoInteractions(mails);
    }

    @Test
    void a_rejection_without_notification_removes_the_account_silently() {
        final KeycloakUser pending = keycloak.addUser("p@fgv.nu", true, true);

        service.reject(admin.id(), pending.id(), "No thanks", false);

        assertThat(keycloak.users).doesNotContainKey(pending.id());
        verifyNoInteractions(mails);
    }
}
