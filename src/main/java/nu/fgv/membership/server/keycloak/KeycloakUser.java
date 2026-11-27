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

package nu.fgv.membership.server.keycloak;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
public record KeycloakUser(String id,
                           @Nullable String username,
                           @Nullable String email,
                           @Nullable String firstName,
                           @Nullable String lastName,
                           boolean enabled,
                           boolean emailVerified,
                           @Nullable Long createdTimestamp,
                           @Nullable Map<String, List<String>> attributes) {

    public String displayName() {
        final String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();

        return name.isEmpty() ? (username == null ? id : username) : name;
    }

    public @Nullable String locale() {
        final List<String> locale = attributes == null ? null : attributes.get("locale");

        return locale == null || locale.isEmpty() ? null : locale.getFirst();
    }
}
