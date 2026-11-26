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

package nu.fgv.membership.server.payment;

import java.util.List;
import java.util.Optional;

/**
 * Spexregister, which knows who the spexare are, read through its {@code MEMBERSHIP} role.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface SpexregisterClient {

    /**
     * @param userId the Keycloak subject
     * @return the spexare the user is linked to
     */
    Optional<Spexare> forUser(String userId);

    List<Spexare> byIds(List<String> ids);

    /**
     * @return the spexare with this email address, if there is exactly one
     */
    Optional<Spexare> byEmail(String email);

    /**
     * @return spexare whose name or nickname contains every word, at most 25
     */
    List<Spexare> search(String query);

    /**
     * Creates a spexare for someone who is not in the register yet, e.g. a new member who paid online. Spexregister links
     * the spexare to the user's account by email.
     */
    Spexare create(String firstName, String lastName, String email);
}
