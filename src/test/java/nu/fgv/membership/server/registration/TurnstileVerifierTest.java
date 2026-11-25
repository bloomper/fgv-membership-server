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

package nu.fgv.membership.server.registration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import nu.fgv.membership.server.config.MembershipProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class TurnstileVerifierTest {

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void accepts_a_response_turnstile_accepts() {
        server.stubFor(post(urlEqualTo("/siteverify")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"success\":true}")));

        assertThat(verifier("secret", false).verify("token", "1.2.3.4")).isTrue();
        server.verify(postRequestedFor(urlEqualTo("/siteverify")).withRequestBody(containing("secret=secret")).withRequestBody(containing("response=token")).withRequestBody(containing("remoteip=1.2.3.4")));
    }

    @Test
    void refuses_a_response_turnstile_refuses() {
        server.stubFor(post(urlEqualTo("/siteverify")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}")));

        assertThat(verifier("secret", false).verify("token", null)).isFalse();
    }

    @Test
    void refuses_when_turnstile_does_not_answer() {
        server.stubFor(post(urlEqualTo("/siteverify")).willReturn(aResponse().withStatus(500)));

        assertThat(verifier("secret", false).verify("token", null)).isFalse();
    }

    @Test
    void refuses_without_a_token_and_does_not_call_turnstile() {
        assertThat(verifier("secret", false).verify(" ", null)).isFalse();
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void refuses_without_a_key_unless_skipping_is_on() {
        assertThat(verifier("", false).verify("token", null)).isFalse();
        assertThat(verifier("", true).verify(null, null)).isTrue();
    }

    private TurnstileVerifier verifier(final String key, final boolean skip) {
        final MembershipProperties properties = new MembershipProperties("http://web", null,
                new MembershipProperties.Turnstile(key, server.baseUrl() + "/siteverify", skip, Duration.ofSeconds(2)), null, null, List.of(), null);

        return new TurnstileVerifier(RestClient.create(), properties);
    }
}
