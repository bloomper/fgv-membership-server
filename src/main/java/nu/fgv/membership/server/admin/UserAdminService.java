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

package nu.fgv.membership.server.admin;

import nu.fgv.membership.server.access.AccessService;
import nu.fgv.membership.server.admin.AccountUser.UserStatus;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.registration.RegistrationRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Listing and finding users for the administrator.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class UserAdminService {

    private final KeycloakAdmin keycloak;
    private final AccessService access;
    private final RegistrationRepository registrations;
    private final MembershipProperties properties;

    public UserAdminService(final KeycloakAdmin keycloak, final AccessService access, final RegistrationRepository registrations,
                            final MembershipProperties properties) {
        this.keycloak = keycloak;
        this.access = access;
        this.registrations = registrations;
        this.properties = properties;
    }

    /**
     * @param search matched against name and email
     * @param status only users with this status; the filter is applied to the page found, except {@code PENDING}, which lists the group
     */
    public Page list(final String search, final UserStatus status, final int first, final int max) {
        if (status == UserStatus.PENDING) {
            final List<AccountUser> pending = keycloak.groupMembers(properties.keycloak().pendingGroup(), first, max).stream()
                    .map(user -> summarize(user, UserStatus.PENDING)).toList();

            return new Page(pending, pending.size() < max ? first + pending.size() : first + max + 1);
        }

        final List<KeycloakUser> found = keycloak.findUsers(search == null ? "" : search, first, max);
        final List<AccountUser> users = found.stream().map(user -> summarize(user, statusOf(user)))
                .filter(user -> status == null || user.status() == status).toList();

        return new Page(users, keycloak.countUsers(search == null ? "" : search));
    }

    public AccountUser get(final String id) {
        final KeycloakUser user = keycloak.findUser(id).orElseThrow(() -> new IllegalArgumentException("Unknown user " + id));

        return AccountUser.of(user, statusOf(user),
                registrations.findFirstByKeycloakUserIdOrderByCreatedAtDesc(id).map(r -> r.getMessage()).orElse(null),
                access.accessOf(id));
    }

    public UserStatus statusOf(final KeycloakUser user) {
        if (!user.enabled()) {
            return UserStatus.DEACTIVATED;
        }
        if (!user.emailVerified()) {
            return UserStatus.UNVERIFIED;
        }
        return keycloak.isMemberOfGroup(user.id(), properties.keycloak().pendingGroup()) ? UserStatus.PENDING : UserStatus.ACTIVE;
    }

    private AccountUser summarize(final KeycloakUser user, final UserStatus status) {
        return AccountUser.of(user, status, null, List.of());
    }

    /**
     * @param items the users found
     * @param total how many users match
     */
    public record Page(List<AccountUser> items, int total) {
    }
}
