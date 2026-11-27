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

import nu.fgv.membership.server.access.ApplicationAccess;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * A user as the administrator sees them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record AccountUser(String id, @Nullable String email, @Nullable String firstName, @Nullable String lastName,
                          String displayName,
                          @Nullable String locale, boolean enabled, boolean emailVerified, UserStatus status,
                          @Nullable Instant createdAt, @Nullable String registrationMessage,
                          List<ApplicationAccess> access) {

    public enum UserStatus {
        /**
         * Verified the address and waits for access to an application.
         */
        PENDING,
        /**
         * Has access and may log in.
         */
        ACTIVE,
        /**
         * Switched off in Keycloak.
         */
        DEACTIVATED,
        /**
         * Registered but has not followed the link in the verification email.
         */
        UNVERIFIED
    }

    static AccountUser of(final KeycloakUser user, final UserStatus status, final String registrationMessage, final List<ApplicationAccess> access) {
        return new AccountUser(user.id(), user.email(), user.firstName(), user.lastName(), user.displayName(), user.locale(),
                user.enabled(), user.emailVerified(), status,
                user.createdTimestamp() == null ? null : Instant.ofEpochMilli(user.createdTimestamp()), registrationMessage, access);
    }
}
