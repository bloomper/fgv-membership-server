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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class RestSpexregisterClient implements SpexregisterClient {

    private static final String FOR_USER = "query($u: ID!) { medlemskapSpexareForUser(userId: $u) { id firstName lastName email } }";
    private static final String BY_IDS = "query($ids: [ID!]!) { medlemskapSpexareByIds(ids: $ids) { id firstName lastName email } }";

    private final HttpSyncGraphQlClient client;

    public RestSpexregisterClient(@Qualifier("keycloakRestClient") final RestClient restClient,
                                  @Value("${membership.spexregister.graphql-url}") final String url) {
        this.client = HttpSyncGraphQlClient.builder(restClient).url(url).build();
    }

    @Override
    public Optional<Spexare> forUser(final String userId) {
        return Optional.ofNullable(execute(FOR_USER, "u", userId).field("medlemskapSpexareForUser").toEntity(Spexare.class));
    }

    @Override
    public List<Spexare> byIds(final List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return execute(BY_IDS, "ids", ids).field("medlemskapSpexareByIds").toEntityList(Spexare.class);
    }

    private ClientGraphQlResponse execute(final String document, final String variable, final Object value) {
        final ClientGraphQlResponse response;

        try {
            response = client.document(document).variable(variable, value).executeSync();
        } catch (final RuntimeException e) {
            throw new IllegalStateException("Spexregister could not be reached: " + e.getMessage(), e);
        }
        if (!response.isValid() || !response.getErrors().isEmpty()) {
            throw new IllegalStateException("Spexregister answered with errors: " + response.getErrors());
        }
        return response;
    }
}
