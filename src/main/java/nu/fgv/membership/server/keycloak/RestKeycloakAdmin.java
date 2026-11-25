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

import nu.fgv.membership.server.config.MembershipProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link KeycloakAdmin} over the admin REST API. The {@code RestClient} sends {@code fgv-membership-service}'s token.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class RestKeycloakAdmin implements KeycloakAdmin {

    private static final ParameterizedTypeReference<List<KeycloakUser>> USERS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> MAPS = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final String admin;

    public RestKeycloakAdmin(final RestClient keycloakRestClient, final MembershipProperties properties) {
        this.client = keycloakRestClient;
        this.admin = properties.keycloak().url() + "/admin/realms/" + properties.keycloak().realm();
    }

    @Override
    public List<KeycloakUser> findUsers(final String search, final int first, final int max) {
        final URI uri = UriComponentsBuilder.fromUriString(admin + "/users")
                .queryParamIfPresent("search", search.isBlank() ? Optional.empty() : Optional.of(search))
                .queryParam("first", first).queryParam("max", max).queryParam("briefRepresentation", false)
                .build().encode().toUri();

        return orEmpty(client.get().uri(uri).retrieve().body(USERS));
    }

    @Override
    public int countUsers(final String search) {
        final URI uri = UriComponentsBuilder.fromUriString(admin + "/users/count")
                .queryParamIfPresent("search", search.isBlank() ? Optional.empty() : Optional.of(search))
                .build().encode().toUri();
        final Integer count = client.get().uri(uri).retrieve().body(Integer.class);

        return count == null ? 0 : count;
    }

    @Override
    public List<KeycloakUser> groupMembers(final String groupName, final int first, final int max) {
        return groupId(groupName)
                .map(id -> orEmpty(client.get().uri(admin + "/groups/{id}/members?first={first}&max={max}&briefRepresentation=false", id, first, max)
                        .retrieve().body(USERS)))
                .orElse(List.of());
    }

    @Override
    public Optional<KeycloakUser> findUser(final String id) {
        try {
            return Optional.ofNullable(client.get().uri(admin + "/users/{id}", id).retrieve().body(KeycloakUser.class));
        } catch (final HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KeycloakUser> findUserByEmail(final String email) {
        final URI uri = UriComponentsBuilder.fromUriString(admin + "/users")
                .queryParam("email", email).queryParam("exact", true).build().encode().toUri();

        return orEmpty(client.get().uri(uri).retrieve().body(USERS)).stream().findFirst();
    }

    @Override
    public String createUser(final String email, final String firstName, final String lastName, final String locale) {
        final Map<String, Object> user = Map.of(
                "username", email, "email", email, "firstName", firstName, "lastName", lastName,
                "enabled", true, "emailVerified", false, "attributes", Map.of("locale", List.of(locale)));
        final URI location = client.post().uri(admin + "/users").contentType(MediaType.APPLICATION_JSON).body(user)
                .retrieve().toBodilessEntity().getHeaders().getLocation();

        if (location == null) {
            throw new KeycloakException("Keycloak did not say where the new user is");
        }

        final String path = location.getPath();

        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void updateUser(final String id, final String firstName, final String lastName, final String email, final String locale) {
        final Map<String, Object> update = Map.of("firstName", firstName, "lastName", lastName, "email", email, "username", email,
                "attributes", Map.of("locale", List.of(locale)));

        put(admin + "/users/" + id, update);
    }

    @Override
    public void setEnabled(final String id, final boolean enabled) {
        put(admin + "/users/" + id, Map.of("enabled", enabled));
    }

    @Override
    public void setEmailVerified(final String id, final boolean verified) {
        put(admin + "/users/" + id, Map.of("emailVerified", verified));
    }

    @Override
    public void deleteUser(final String id) {
        client.delete().uri(admin + "/users/{id}", id).retrieve().toBodilessEntity();
    }

    @Override
    public void logout(final String id) {
        client.post().uri(admin + "/users/{id}/logout", id).retrieve().toBodilessEntity();
    }

    @Override
    public void joinGroup(final String id, final String groupName) {
        groupId(groupName).ifPresentOrElse(
                group -> client.put().uri(admin + "/users/{id}/groups/{group}", id, group).retrieve().toBodilessEntity(),
                () -> {
                    throw new KeycloakException("The group " + groupName + " does not exist");
                });
    }

    @Override
    public void leaveGroup(final String id, final String groupName) {
        groupId(groupName).ifPresent(group -> client.delete().uri(admin + "/users/{id}/groups/{group}", id, group).retrieve().toBodilessEntity());
    }

    @Override
    public boolean isMemberOfGroup(final String id, final String groupName) {
        final List<Map<String, Object>> groups = orEmpty(client.get().uri(admin + "/users/{id}/groups", id).retrieve().body(MAPS));

        return groups.stream().anyMatch(group -> groupName.equals(group.get("name")));
    }

    @Override
    public Optional<KeycloakClient> findClient(final String clientId) {
        final URI uri = UriComponentsBuilder.fromUriString(admin + "/clients").queryParam("clientId", clientId).build().encode().toUri();

        return orEmpty(client.get().uri(uri).retrieve().body(MAPS)).stream().findFirst().map(c -> new KeycloakClient(
                (String) c.get("id"), clientId, (String) c.get("name"), (String) c.get("baseUrl")));
    }

    @Override
    public List<String> clientRoles(final String userId, final KeycloakClient keycloakClient) {
        return orEmpty(client.get().uri(admin + "/users/{user}/role-mappings/clients/{client}", userId, keycloakClient.id())
                .retrieve().body(MAPS)).stream().map(role -> (String) role.get("name")).toList();
    }

    @Override
    public List<KeycloakUser> usersWithClientRole(final KeycloakClient keycloakClient, final String role) {
        return orEmpty(client.get().uri(admin + "/clients/{id}/roles/{role}/users?max=1000", keycloakClient.id(), role).retrieve().body(USERS));
    }

    @Override
    public void addClientRoles(final String userId, final KeycloakClient keycloakClient, final List<String> roles) {
        if (!roles.isEmpty()) {
            client.post().uri(admin + "/users/{user}/role-mappings/clients/{client}", userId, keycloakClient.id())
                    .contentType(MediaType.APPLICATION_JSON).body(roleRepresentations(keycloakClient, roles)).retrieve().toBodilessEntity();
        }
    }

    @Override
    public void removeClientRoles(final String userId, final KeycloakClient keycloakClient, final List<String> roles) {
        if (!roles.isEmpty()) {
            client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(admin + "/users/{user}/role-mappings/clients/{client}", userId, keycloakClient.id())
                    .contentType(MediaType.APPLICATION_JSON).body(roleRepresentations(keycloakClient, roles)).retrieve().toBodilessEntity();
        }
    }

    @Override
    public void sendSetPasswordEmail(final String id, final String clientId, final @Nullable String redirectUri, final int lifespanSeconds) {
        sendActions(id, clientId, redirectUri, lifespanSeconds, List.of("UPDATE_PASSWORD"));
    }

    @Override
    public void sendResetPasswordEmail(final String id, final String clientId, final @Nullable String redirectUri, final int lifespanSeconds) {
        sendActions(id, clientId, redirectUri, lifespanSeconds, List.of("UPDATE_PASSWORD"));
    }

    private void sendActions(final String id, final String clientId, final @Nullable String redirectUri, final int lifespan, final List<String> actions) {
        final URI uri = UriComponentsBuilder.fromUriString(admin + "/users/" + id + "/execute-actions-email")
                .queryParam("client_id", clientId).queryParamIfPresent("redirect_uri", Optional.ofNullable(redirectUri)).queryParam("lifespan", lifespan)
                .build().encode().toUri();

        client.put().uri(uri).contentType(MediaType.APPLICATION_JSON).body(actions).retrieve().toBodilessEntity();
    }

    private List<Map<String, Object>> roleRepresentations(final KeycloakClient keycloakClient, final List<String> roles) {
        final List<Map<String, Object>> available = orEmpty(client.get().uri(admin + "/clients/{id}/roles", keycloakClient.id()).retrieve().body(MAPS));

        return roles.stream().map(name -> available.stream().filter(role -> name.equals(role.get("name"))).findFirst()
                .orElseThrow(() -> new KeycloakException("The client " + keycloakClient.clientId() + " has no role " + name))).toList();
    }

    private Optional<String> groupId(final String name) {
        final URI uri = UriComponentsBuilder.fromUriString(admin + "/groups").queryParam("search", name).queryParam("exact", true).build().encode().toUri();

        return orEmpty(client.get().uri(uri).retrieve().body(MAPS)).stream().filter(group -> name.equals(group.get("name"))).findFirst()
                .map(group -> (String) group.get("id"));
    }

    private void put(final String uri, final Object body) {
        // Keycloak replaces the user with what is sent, so what is sent is the current user with the changes
        final String id = uri.substring(uri.lastIndexOf('/') + 1);
        final Map<String, Object> current = client.get().uri(uri).retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
        });
        final Map<String, Object> merged = new java.util.LinkedHashMap<>(current == null ? Map.of() : current);

        merged.putAll(asMap(body));
        client.put().uri(uri).contentType(MediaType.APPLICATION_JSON).body(merged).retrieve().onStatus(HttpStatusCode::isError, (request, response) -> {
            throw new KeycloakException("Keycloak refused to update user " + id + ": " + response.getStatusCode());
        }).toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object body) {
        return (Map<String, Object>) body;
    }

    private static <T> List<T> orEmpty(final @Nullable List<T> list) {
        return list == null ? List.of() : list;
    }
}
