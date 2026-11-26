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

package nu.fgv.membership.server.application;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * Applying for membership, and the board's answer.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ApplicationGraphqlApi {

    private static final String USER = "hasRole('fgv-membership_USER')";
    private static final String BOARD = "hasRole('fgv-membership_BOARD')";

    private final ApplicationService service;
    private final nu.fgv.membership.server.board.AuditService audit;

    public ApplicationGraphqlApi(final ApplicationService service, final nu.fgv.membership.server.board.AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @QueryMapping
    @PreAuthorize(USER)
    public ApplicationService.Item myApplication(@AuthenticationPrincipal final Jwt caller) {
        return service.mine(caller.getSubject()).orElse(null);
    }

    @MutationMapping
    @PreAuthorize(USER)
    public ApplicationService.Item applyForMembership(@AuthenticationPrincipal final Jwt caller, @Argument final String name, @Argument final String text) {
        return service.apply(caller.getSubject(), name, text);
    }

    @QueryMapping
    @PreAuthorize(BOARD)
    public List<ApplicationService.Item> membershipApplications() {
        return service.pending();
    }

    @MutationMapping
    @PreAuthorize(BOARD)
    public ApplicationService.Item acceptApplication(@AuthenticationPrincipal final Jwt caller, @Argument final String id, @Argument final String spexareId) {
        final ApplicationService.Item item = service.accept(caller.getSubject(), id, spexareId);

        audit.log(caller.getSubject(), "APPLICATION_ACCEPTED", id, item.applicantName());
        return item;
    }

    @MutationMapping
    @PreAuthorize(BOARD)
    public ApplicationService.Item rejectApplication(@AuthenticationPrincipal final Jwt caller, @Argument final String id, @Argument final String message) {
        final ApplicationService.Item item = service.reject(caller.getSubject(), id, message);

        audit.log(caller.getSubject(), "APPLICATION_REJECTED", id, item.applicantName());
        return item;
    }
}
