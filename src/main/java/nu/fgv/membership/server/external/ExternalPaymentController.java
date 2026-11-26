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

package nu.fgv.membership.server.external;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /api/external-payments}: the WordPress shop tells that someone paid for a year. It needs the
 * {@code PAYMENT} role of {@code fgv-membership}, which only the shop's service account holds.
 *
 * <p>Answers {@code 201} with {@code RECORDED}, {@code 200} with {@code DUPLICATE} (told before) or
 * {@code ALREADY_MEMBER} (they were already a member that year, so nothing was recorded), and {@code 400} with a message
 * when the request is not valid.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class ExternalPaymentController {

    private final ExternalPaymentService service;

    public ExternalPaymentController(final ExternalPaymentService service) {
        this.service = service;
    }

    @PostMapping("/api/external-payments")
    @PreAuthorize("hasRole('fgv-membership_PAYMENT')")
    public ResponseEntity<ExternalPaymentService.Result> pay(@AuthenticationPrincipal final Jwt caller,
                                                             @RequestBody final ExternalPaymentRequest request) {
        final ExternalPaymentService.Result result = service.record(caller.getSubject(), request);

        return ResponseEntity.status(result.status() == ExternalPaymentService.Status.RECORDED ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(final IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
