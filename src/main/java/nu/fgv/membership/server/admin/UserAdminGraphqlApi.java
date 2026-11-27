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
import nu.fgv.membership.server.access.ApplicationAccess;
import nu.fgv.membership.server.access.ApplicationCatalog;
import nu.fgv.membership.server.admin.AccountUser.UserStatus;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.Mailer;
import nu.fgv.membership.server.registration.RegistrationService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * User administration, for {@code ADMIN} only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
@PreAuthorize("hasRole('fgv-membership_ADMIN')")
public class UserAdminGraphqlApi {

    private static final int SET_PASSWORD_LIFESPAN = 7 * 24 * 3600;

    private final UserAdminService users;
    private final AccessService access;
    private final ApplicationCatalog catalog;
    private final KeycloakAdmin keycloak;
    private final RegistrationService registration;
    private final MembershipProperties properties;

    public UserAdminGraphqlApi(final UserAdminService users, final AccessService access, final ApplicationCatalog catalog,
                               final KeycloakAdmin keycloak, final RegistrationService registration, final MembershipProperties properties) {
        this.users = users;
        this.access = access;
        this.catalog = catalog;
        this.keycloak = keycloak;
        this.registration = registration;
        this.properties = properties;
    }

    @QueryMapping
    public UserAdminService.Page users(@Argument final String search, @Argument final UserStatus status,
                                       @Argument final Integer first, @Argument final Integer max) {
        return users.list(search, status, first == null ? 0 : first, max == null ? 25 : Math.min(max, 100));
    }

    @QueryMapping
    public AccountUser user(@Argument final String id) {
        return users.get(id);
    }

    @QueryMapping
    public List<ApplicationInfo> applications() {
        return catalog.all().stream().map(entry -> new ApplicationInfo(entry.client().clientId(), entry.client().displayName(),
                entry.client().baseUrl(), entry.ladder().roles(), entry.application().defaultAccess())).toList();
    }

    @MutationMapping
    public AccountUser setUserEnabled(@AuthenticationPrincipal final Jwt actor, @Argument final String id, @Argument final boolean enabled) {
        access.setEnabled(actor.getSubject(), id, enabled);
        return users.get(id);
    }

    @MutationMapping
    public ApplicationAccess setApplicationAccess(@AuthenticationPrincipal final Jwt actor, @Argument final String userId,
                                                  @Argument final String clientId, @Argument final String role,
                                                  @Argument final Boolean notify) {
        return access.setAccess(actor.getSubject(), userId, clientId, role, notify == null || notify);
    }

    @MutationMapping
    public boolean rejectUser(@AuthenticationPrincipal final Jwt actor, @Argument final String id, @Argument final String message,
                                @Argument final Boolean notify) {
        access.reject(actor.getSubject(), id, message, notify == null || notify);
        return true;
    }

    @MutationMapping
    public AccountUser updateUser(@Argument final String id, @Argument final UserInput input) {
        final KeycloakUser user = keycloak.findUser(id).orElseThrow(() -> new IllegalArgumentException("Unknown user " + id));

        keycloak.updateUser(id, input.firstName(), input.lastName(), input.email(),
                input.locale() == null ? Mailer.localeOf(user.locale()).getLanguage() : input.locale());
        return users.get(id);
    }

    @MutationMapping
    public boolean resendVerification(@Argument final String id) {
        registration.resendVerification(id);
        return true;
    }

    @MutationMapping
    public boolean sendSetPasswordEmail(@Argument final String id) {
        keycloak.sendSetPasswordEmail(id, "fgv-membership", null, SET_PASSWORD_LIFESPAN);
        return true;
    }

    @MutationMapping
    public boolean sendPasswordResetEmail(@Argument final String id) {
        keycloak.sendResetPasswordEmail(id, "fgv-membership", null, SET_PASSWORD_LIFESPAN);
        return true;
    }

    public record UserInput(String firstName, String lastName, String email, String locale) {
    }

    public record ApplicationInfo(String clientId, String name, String url, List<String> ladder, boolean defaultAccess) {
    }
}
