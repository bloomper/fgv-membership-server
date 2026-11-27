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
import nu.fgv.membership.server.keycloak.KeycloakClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The FGV applications whose access is administered: the configured ones that exist in the realm and have an absolute
 * address, with their names and addresses read from the realm (the same rule as the approval email had).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ApplicationCatalog {

    private final MembershipProperties properties;
    private final KeycloakAdmin keycloak;

    public ApplicationCatalog(final MembershipProperties properties, final KeycloakAdmin keycloak) {
        this.properties = properties;
        this.keycloak = keycloak;
    }

    public List<Entry> all() {
        return properties.applications().stream()
                .flatMap(application -> keycloak.findClient(application.clientId())
                        .filter(KeycloakClient::hasAddress)
                        .map(client -> new Entry(client, application, new RoleLadder(application.ladder())))
                        .stream())
                .toList();
    }

    public Optional<Entry> find(final String clientId) {
        return all().stream().filter(entry -> entry.client().clientId().equals(clientId)).findFirst();
    }

    /**
     * @param client      what Keycloak says about it
     * @param application what is configured about it
     * @param ladder      the roles people hold
     */
    public record Entry(KeycloakClient client, MembershipProperties.Application application, RoleLadder ladder) {
    }
}
