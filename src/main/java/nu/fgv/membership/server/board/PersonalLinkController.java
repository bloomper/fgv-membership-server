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

package nu.fgv.membership.server.board;

import jakarta.servlet.http.HttpServletRequest;
import nu.fgv.membership.server.config.MembershipProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * The page behind a personal link: public, since the token is the key. It shows the payment request and lets the payer
 * say they have paid or that they want no more reminders, and does nothing else. Guessing is limited per address.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
@RequestMapping("/api/personal")
public class PersonalLinkController {

    private final PersonalLinkService service;
    private final nu.fgv.membership.server.registration.RateLimiter limiter;

    public PersonalLinkController(final PersonalLinkService service, final Clock clock, final MembershipProperties properties) {
        this.service = service;
        this.limiter = new nu.fgv.membership.server.registration.RateLimiter(120, Duration.ofHours(1), clock);
    }

    public record Report(String paidBy) {
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> page(@PathVariable final String token, final HttpServletRequest http) {
        if (!limiter.tryAcquire(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return service.page(token).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{token}/report")
    public ResponseEntity<Map<String, Boolean>> report(@PathVariable final String token, @RequestBody(required = false) final Report report,
                                                       final HttpServletRequest http) {
        if (!limiter.tryAcquire(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return service.report(token, report == null ? null : report.paidBy()) ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }

    @PostMapping("/{token}/opt-out")
    public ResponseEntity<Map<String, Boolean>> optOut(@PathVariable final String token, final HttpServletRequest http) {
        if (!limiter.tryAcquire(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return service.optOut(token) ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.notFound().build();
    }
}
