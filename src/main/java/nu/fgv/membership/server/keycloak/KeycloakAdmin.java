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
import java.util.Optional;

/**
 * What Medlemskap does in Keycloak, with {@code fgv-membership-service}. A port, so that the services around it can be
 * tested without a Keycloak.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface KeycloakAdmin {

    /**
     * @param search matched against name and email; may be empty
     */
    List<KeycloakUser> findUsers(String search, int first, int max);

    int countUsers(String search);

    /**
     * @return the members of a group, by user id
     */
    List<KeycloakUser> groupMembers(String groupName, int first, int max);

    Optional<KeycloakUser> findUser(String id);

    Optional<KeycloakUser> findUserByEmail(String email);

    /**
     * Creates an enabled user without a password and with an unverified email address.
     *
     * @return the new user's id
     */
    String createUser(String email, String firstName, String lastName, String locale);

    void updateUser(String id, String firstName, String lastName, String email, String locale);

    void setEnabled(String id, boolean enabled);

    void setEmailVerified(String id, boolean verified);

    void deleteUser(String id);

    void logout(String id);

    void joinGroup(String id, String groupName);

    void leaveGroup(String id, String groupName);

    boolean isMemberOfGroup(String id, String groupName);

    /**
     * @return what the client is called and where it lives, if it exists
     */
    Optional<KeycloakClient> findClient(String clientId);

    /**
     * @return the names of the roles the user holds directly on the client
     */
    List<String> clientRoles(String userId, KeycloakClient client);

    /**
     * @return the users who hold the role directly
     */
    List<KeycloakUser> usersWithClientRole(KeycloakClient client, String role);

    void addClientRoles(String userId, KeycloakClient client, List<String> roles);

    void removeClientRoles(String userId, KeycloakClient client, List<String> roles);

    /**
     * Asks Keycloak to email the user a link that sets a password and then sends them to {@code redirectUri}.
     */
    void sendSetPasswordEmail(String id, String clientId, @Nullable String redirectUri, int lifespanSeconds);

    /**
     * Asks Keycloak to email the user a link that resets the password.
     */
    void sendResetPasswordEmail(String id, String clientId, @Nullable String redirectUri, int lifespanSeconds);
}
