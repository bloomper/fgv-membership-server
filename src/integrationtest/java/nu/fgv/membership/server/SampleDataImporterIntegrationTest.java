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

import nu.fgv.membership.server.util.data.SampleDataImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample data, imported against Spexregister's sample users (WireMock) and a real Keycloak.
 */
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SampleDataImporter importer;

    @Test
    void importsSampleDataAndCanBeRepeated() {
        for (int i = 1; i <= 45; i++) {
            final String user = "00000000-0000-4000-8000-%012d".formatted(1000 + i);
            final String data = "{\"medlemskapSpexareForUser\":" + person("spexare-%02d".formatted(i), "Förnamn" + i, "Efternamn" + i, null) + "}";

            SPEXREGISTER.stubFor(post(urlEqualTo("/spexregister/graphql")).withRequestBody(containing(user))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"data\":" + data + "}")));
        }

        importer.importSampleData();
        importer.importSampleData();

        assertThat(count("membership")).isGreaterThan(50);
        assertThat(count("fee_level")).isGreaterThan(10);
        assertThat(count("payment_request")).isGreaterThan(10);
        assertThat(count("payment")).isGreaterThan(5);
        assertThat(count("personal_link")).isEqualTo(2);
        assertThat(count("bank_format")).isEqualTo(2);
        assertThat(count("application")).isEqualTo(2);
        assertThat(count("registration")).isEqualTo(2);
        assertThat(count("membership_change")).isGreaterThan(0);
    }

    private long count(final String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
