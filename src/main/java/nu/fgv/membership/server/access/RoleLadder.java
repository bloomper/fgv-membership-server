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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An application's roles that people hold, lowest first. The roles in Keycloak are plain, not composite, so
 * "ADMIN also gives EDITOR and USER" is done here: granting a role grants every role below it, and revoking a role
 * revokes every role above it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record RoleLadder(List<String> roles) {

    public RoleLadder {
        roles = List.copyOf(roles);
    }

    public boolean contains(final String role) {
        return roles.contains(role);
    }

    /**
     * @return the roles to hold when {@code role} is the highest: it and everything below it
     */
    public List<String> grantedBy(final String role) {
        final int index = roles.indexOf(role);

        if (index < 0) {
            throw new IllegalArgumentException("Unknown role " + role);
        }
        return roles.subList(0, index + 1);
    }

    /**
     * @return the highest role among those held, ignoring roles that are not on the ladder (service roles)
     */
    public Optional<String> highest(final List<String> held) {
        String highest = null;

        for (final String role : roles) {
            if (held.contains(role)) {
                highest = role;
            }
        }
        return Optional.ofNullable(highest);
    }

    /**
     * @return whether the held roles are exactly what their highest role grants, with nothing missing
     */
    public boolean isConsistent(final List<String> held) {
        return highest(held).map(top -> held.containsAll(grantedBy(top))).orElse(true);
    }

    /**
     * @return the roles to add and remove, so that exactly {@code grantedBy(target)} of the ladder is held; roles that
     * are not on the ladder are left alone. A {@code null} target removes access altogether.
     */
    public Change change(final List<String> held, final String target) {
        final List<String> wanted = target == null ? List.of() : grantedBy(target);
        final List<String> add = new ArrayList<>();
        final List<String> remove = new ArrayList<>();

        wanted.stream().filter(role -> !held.contains(role)).forEach(add::add);
        roles.stream().filter(role -> held.contains(role) && !wanted.contains(role)).forEach(remove::add);
        return new Change(add, remove);
    }

    /**
     * @param add    roles to give
     * @param remove roles to take away
     */
    public record Change(List<String> add, List<String> remove) {

        public boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty();
        }
    }
}
