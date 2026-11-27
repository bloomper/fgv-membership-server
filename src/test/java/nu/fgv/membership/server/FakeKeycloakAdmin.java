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

package nu.fgv.membership.server;

import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakClient;
import nu.fgv.membership.server.keycloak.KeycloakUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory Keycloak for tests.
 */
public class FakeKeycloakAdmin implements KeycloakAdmin {

    public final Map<String, KeycloakUser> users = new HashMap<>();
    public final Map<String, Set<String>> groups = new HashMap<>();
    public final Map<String, Set<String>> roles = new HashMap<>();
    public final Map<String, KeycloakClient> clients = new HashMap<>();
    public final List<String> passwordEmails = new ArrayList<>();
    public final List<String> loggedOut = new ArrayList<>();

    public FakeKeycloakAdmin withClient(final String clientId, final String name, final String url) {
        clients.put(clientId, new KeycloakClient("uuid-" + clientId, clientId, name, url));
        return this;
    }

    public KeycloakUser addUser(final String email, final boolean pending, final boolean verified) {
        final String id = UUID.randomUUID().toString();
        final KeycloakUser user = new KeycloakUser(id, email, email, "First", "Last", true, verified, 0L, Map.of("locale", List.of("sv")));

        users.put(id, user);
        if (pending) {
            groups.computeIfAbsent("pending-approval", g -> new HashSet<>()).add(id);
        }
        return user;
    }

    public void giveRoles(final String userId, final String clientId, final String... names) {
        roles.computeIfAbsent(userId + "/" + clientId, k -> new HashSet<>()).addAll(List.of(names));
    }

    public Set<String> rolesOf(final String userId, final String clientId) {
        return roles.getOrDefault(userId + "/" + clientId, Set.of());
    }

    @Override
    public List<KeycloakUser> findUsers(final String search, final int first, final int max) {
        return users.values().stream().filter(u -> search.isBlank() || u.email().contains(search)).skip(first).limit(max).toList();
    }

    @Override
    public int countUsers(final String search) {
        return findUsers(search, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public List<KeycloakUser> groupMembers(final String groupName, final int first, final int max) {
        return groups.getOrDefault(groupName, Set.of()).stream().map(users::get).skip(first).limit(max).toList();
    }

    @Override
    public Optional<KeycloakUser> findUser(final String id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<KeycloakUser> findUserByEmail(final String email) {
        return users.values().stream().filter(u -> email.equals(u.email())).findFirst();
    }

    @Override
    public String createUser(final String email, final String firstName, final String lastName, final String locale) {
        final String id = UUID.randomUUID().toString();

        users.put(id, new KeycloakUser(id, email, email, firstName, lastName, true, false, 0L, Map.of("locale", List.of(locale))));
        return id;
    }

    @Override
    public void updateUser(final String id, final String firstName, final String lastName, final String email, final String locale) {
        final KeycloakUser u = users.get(id);

        users.put(id, new KeycloakUser(id, email, email, firstName, lastName, u.enabled(), u.emailVerified(), u.createdTimestamp(), Map.of("locale", List.of(locale))));
    }

    @Override
    public void setEnabled(final String id, final boolean enabled) {
        final KeycloakUser u = users.get(id);

        users.put(id, new KeycloakUser(id, u.username(), u.email(), u.firstName(), u.lastName(), enabled, u.emailVerified(), u.createdTimestamp(), u.attributes()));
    }

    @Override
    public void setEmailVerified(final String id, final boolean verified) {
        final KeycloakUser u = users.get(id);

        users.put(id, new KeycloakUser(id, u.username(), u.email(), u.firstName(), u.lastName(), u.enabled(), verified, u.createdTimestamp(), u.attributes()));
    }

    @Override
    public void deleteUser(final String id) {
        users.remove(id);
        groups.values().forEach(g -> g.remove(id));
    }

    @Override
    public void logout(final String id) {
        loggedOut.add(id);
    }

    @Override
    public void joinGroup(final String id, final String groupName) {
        groups.computeIfAbsent(groupName, g -> new HashSet<>()).add(id);
    }

    @Override
    public void leaveGroup(final String id, final String groupName) {
        groups.getOrDefault(groupName, new HashSet<>()).remove(id);
    }

    @Override
    public boolean isMemberOfGroup(final String id, final String groupName) {
        return groups.getOrDefault(groupName, Set.of()).contains(id);
    }

    @Override
    public Optional<KeycloakClient> findClient(final String clientId) {
        return Optional.ofNullable(clients.get(clientId));
    }

    @Override
    public List<String> clientRoles(final String userId, final KeycloakClient client) {
        return List.copyOf(rolesOf(userId, client.clientId()));
    }

    @Override
    public List<KeycloakUser> usersWithClientRole(final KeycloakClient client, final String role) {
        return users.values().stream().filter(u -> rolesOf(u.id(), client.clientId()).contains(role)).toList();
    }

    @Override
    public void addClientRoles(final String userId, final KeycloakClient client, final List<String> names) {
        giveRoles(userId, client.clientId(), names.toArray(String[]::new));
    }

    @Override
    public void removeClientRoles(final String userId, final KeycloakClient client, final List<String> names) {
        roles.getOrDefault(userId + "/" + client.clientId(), new HashSet<>()).removeAll(names);
    }

    @Override
    public void sendSetPasswordEmail(final String id, final String clientId, final String redirectUri, final int lifespanSeconds) {
        passwordEmails.add(id);
    }

    @Override
    public void sendResetPasswordEmail(final String id, final String clientId, final String redirectUri, final int lifespanSeconds) {
        passwordEmails.add(id);
    }
}
