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

import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.AccessMails;
import nu.fgv.membership.server.util.security.MembershipRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Gives and takes away access to FGV's applications. Keycloak holds the roles; this decides which roles go together,
 * moves a user out of the pending group when they are given their first access, and sends the emails that Keycloak's
 * listener used to send.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AccessService {

    private final KeycloakAdmin keycloak;
    private final ApplicationCatalog catalog;
    private final AccessChangeRepository changes;
    private final AccessMails mails;
    private final MembershipProperties properties;

    public AccessService(final KeycloakAdmin keycloak, final ApplicationCatalog catalog, final AccessChangeRepository changes,
                         final AccessMails mails, final MembershipProperties properties) {
        this.keycloak = keycloak;
        this.catalog = catalog;
        this.changes = changes;
        this.mails = mails;
        this.properties = properties;
    }

    public List<ApplicationAccess> accessOf(final String userId) {
        return catalog.all().stream().map(entry -> {
            final List<String> held = keycloak.clientRoles(userId, entry.client());
            final List<String> onLadder = entry.ladder().roles().stream().filter(held::contains).toList();
            final String highest = entry.ladder().highest(held).orElse(null);

            return new ApplicationAccess(entry.client().clientId(), entry.client().displayName(), entry.client().baseUrl(),
                    entry.ladder().roles(), onLadder, highest, highest != null, entry.ladder().isConsistent(held),
                    entry.application().defaultAccess());
        }).toList();
    }

    /**
     * Sets the highest role a user holds on an application, giving the roles below it and taking away the ones above;
     * {@code null} takes away all access to the application.
     *
     * @param actor the administrator, by Keycloak subject
     */
    public ApplicationAccess setAccess(final String actor, final String userId, final String clientId, final String role) {
        final ApplicationCatalog.Entry entry = catalog.find(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown application " + clientId));
        final KeycloakUser user = keycloak.findUser(userId).orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));

        if (role != null && !entry.ladder().contains(role)) {
            throw new IllegalArgumentException(role + " is not a role of " + clientId);
        }
        guardAdministrators(actor, user, entry, role);

        final boolean wasApproved = isApproved(user);
        final Set<String> applicationsBefore = new LinkedHashSet<>(applicationsOf(userId));
        final List<String> held = keycloak.clientRoles(userId, entry.client());
        final RoleLadder.Change change = entry.ladder().change(held, role);

        if (!change.isEmpty()) {
            keycloak.addClientRoles(userId, entry.client(), change.add());
            keycloak.removeClientRoles(userId, entry.client(), change.remove());
            changes.save(new AccessChange(userId, actor, "ACCESS", clientId, String.join(",", held),
                    String.join(",", entry.ladder().roles().stream().filter(r -> role != null && entry.ladder().grantedBy(role).contains(r)).toList())));
        }

        if (role != null && !applicationsBefore.contains(clientId)) {
            afterGrant(user, wasApproved, applicationsBefore, entry);
        }
        return accessOf(userId).stream().filter(a -> a.clientId().equals(clientId)).findFirst().orElseThrow();
    }

    public KeycloakUser setEnabled(final String actor, final String userId, final boolean enabled) {
        final KeycloakUser user = keycloak.findUser(userId).orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));

        if (!enabled && actor.equals(userId)) {
            throw new IllegalStateException("You cannot deactivate yourself");
        }
        if (!enabled && isLastAdministrator(user)) {
            throw new IllegalStateException("The last administrator cannot be deactivated");
        }
        keycloak.setEnabled(userId, enabled);
        if (!enabled) {
            keycloak.logout(userId);
        }
        changes.save(new AccessChange(userId, actor, enabled ? "ACTIVATE" : "DEACTIVATE", null, null, null));
        return keycloak.findUser(userId).orElse(user);
    }

    /**
     * Removes a pending user's account, with an optional message by email.
     */
    public void reject(final String actor, final String userId, final String message) {
        final KeycloakUser user = keycloak.findUser(userId).orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));

        if (isApproved(user)) {
            throw new IllegalStateException("Only users who wait for approval can be rejected");
        }
        keycloak.deleteUser(userId);
        changes.save(new AccessChange(userId, actor, "REJECT", null, null, null));
        mails.rejected(user, message);
    }

    /**
     * @return whether the user holds any role beyond the realm's default role, which is what Keycloak's
     * {@code fgv-approval} checks at login. Pending users are in the group.
     */
    public boolean isApproved(final KeycloakUser user) {
        return !keycloak.isMemberOfGroup(user.id(), properties.keycloak().pendingGroup());
    }

    private void afterGrant(final KeycloakUser user, final boolean wasApproved, final Set<String> before, final ApplicationCatalog.Entry entry) {
        if (!wasApproved) {
            keycloak.leaveGroup(user.id(), properties.keycloak().pendingGroup());
            mails.approved(user, applicationsWithAccess(user.id()));
        } else {
            mails.firstAccess(user, entry.client());
        }
    }

    private List<String> applicationsOf(final String userId) {
        return accessOf(userId).stream().filter(ApplicationAccess::enabled).map(ApplicationAccess::clientId).toList();
    }

    private List<ApplicationAccess> applicationsWithAccess(final String userId) {
        final List<ApplicationAccess> result = new ArrayList<>();

        accessOf(userId).forEach(access -> {
            if (access.enabled() || access.defaultAccess()) {
                result.add(access);
            }
        });
        return result;
    }

    private void guardAdministrators(final String actor, final KeycloakUser user, final ApplicationCatalog.Entry entry, final String role) {
        if (!entry.client().clientId().equals(MembershipRole.CLIENT_ID)) {
            return;
        }

        final boolean losesAdmin = !MembershipRole.ADMIN.name().equals(role)
                && keycloak.clientRoles(user.id(), entry.client()).contains(MembershipRole.ADMIN.name());

        if (losesAdmin && actor.equals(user.id())) {
            throw new IllegalStateException("You cannot demote yourself");
        }
        if (losesAdmin && isLastAdministrator(user)) {
            throw new IllegalStateException("The last administrator cannot be demoted");
        }
    }

    private boolean isLastAdministrator(final KeycloakUser user) {
        final Optional<ApplicationCatalog.Entry> membership = catalog.find(MembershipRole.CLIENT_ID);

        if (membership.isEmpty() || !keycloak.clientRoles(user.id(), membership.get().client()).contains(MembershipRole.ADMIN.name())) {
            return false;
        }
        return keycloak.usersWithClientRole(membership.get().client(), MembershipRole.ADMIN.name()).stream()
                .filter(KeycloakUser::enabled).allMatch(admin -> admin.id().equals(user.id()));
    }
}
