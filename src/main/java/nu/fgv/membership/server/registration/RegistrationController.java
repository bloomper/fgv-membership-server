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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import nu.fgv.membership.server.config.MembershipProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * The public endpoints of registration. Everything is answered the same whether or not the address has an account.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
@RequestMapping("/api/registration")
public class RegistrationController {

    private final RegistrationService service;
    private final TurnstileVerifier turnstile;
    private final RateLimiter perIp;
    private final RateLimiter perEmail;
    private final RateLimiter verifyPerIp;

    public RegistrationController(final RegistrationService service, final TurnstileVerifier turnstile,
                                  final MembershipProperties properties, final Clock clock) {
        this.service = service;
        this.turnstile = turnstile;
        this.perIp = new RateLimiter(properties.registration().maxPerHourPerIp(), Duration.ofHours(1), clock);
        this.perEmail = new RateLimiter(properties.registration().maxPerHourPerEmail(), Duration.ofHours(1), clock);
        this.verifyPerIp = new RateLimiter(60, Duration.ofHours(1), clock);
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody final RegistrationRequest request, final HttpServletRequest http) {
        final String ip = http.getRemoteAddr();

        if (!perIp.tryAcquire(ip) || !perEmail.tryAcquire(request.email().strip().toLowerCase(Locale.ROOT))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate-limited"));
        }
        // The challenge first: nothing else is done for someone who has not passed it
        if (!turnstile.verify(request.turnstileToken(), ip)) {
            return ResponseEntity.badRequest().body(Map.of("error", "challenge-failed"));
        }
        service.register(request);
        return ResponseEntity.accepted().body(Map.of("status", "verification-sent"));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@RequestBody final Map<String, String> body, final HttpServletRequest http) {
        if (!verifyPerIp.tryAcquire(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate-limited"));
        }
        return service.verify(body.get("token"))
                ? ResponseEntity.ok(Map.of("status", "verified"))
                : ResponseEntity.badRequest().body(Map.of("error", "invalid-token"));
    }
}
