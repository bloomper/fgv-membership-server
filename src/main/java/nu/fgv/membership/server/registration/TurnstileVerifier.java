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

import nu.fgv.membership.server.config.MembershipProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Verifies a Cloudflare Turnstile response with {@code siteverify}. It says no whenever it cannot say yes: a missing
 * token, a refusal, an answer that is not understood, or no answer. Without a secret key it refuses, unless
 * {@code membership.turnstile.skip-without-key} is on, which only the local and test profiles do.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    private final RestClient client;
    private final MembershipProperties.Turnstile properties;

    public TurnstileVerifier(final RestClient plainRestClient, final MembershipProperties properties) {
        this.client = plainRestClient;
        this.properties = properties.turnstile();
    }

    public boolean verify(final @Nullable String token, final @Nullable String remoteIp) {
        if (!StringUtils.hasText(properties.secretKey())) {
            if (properties.skipWithoutKey()) {
                return true;
            }
            log.error("No Turnstile secret key is configured, registration is refused");
            return false;
        }
        if (!StringUtils.hasText(token)) {
            return false;
        }

        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

        form.add("secret", properties.secretKey());
        form.add("response", token);
        if (StringUtils.hasText(remoteIp)) {
            form.add("remoteip", remoteIp);
        }

        try {
            final Map<String, Object> result = client.post().uri(properties.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(new ParameterizedTypeReference<>() {
                    });

            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                return true;
            }
            log.debug("Turnstile rejected the response: {}", result == null ? null : result.get("error-codes"));
            return false;
        } catch (final RestClientException e) {
            log.warn("Could not verify the Turnstile response", e);
            return false;
        }
    }
}
