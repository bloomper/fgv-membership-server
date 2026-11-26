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

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
@PreAuthorize("hasRole('fgv-membership_ADMIN')")
public class DefaultAccessGraphqlApi {

    private final DefaultAccessService service;
    private final nu.fgv.membership.server.board.AuditService audit;

    public DefaultAccessGraphqlApi(final DefaultAccessService service, final nu.fgv.membership.server.board.AuditService audit) {
        this.audit = audit;
        this.service = service;
    }

    @QueryMapping
    public List<DefaultAccessService.Entry> defaultAccess(@Argument final nu.fgv.membership.server.payment.AccessProfile profile) {
        return service.all(profile == null ? nu.fgv.membership.server.payment.AccessProfile.MEMBER : profile);
    }

    @MutationMapping
    public DefaultAccessService.Entry setDefaultAccess(@org.springframework.security.core.annotation.AuthenticationPrincipal final org.springframework.security.oauth2.jwt.Jwt caller,
                                                       @Argument final nu.fgv.membership.server.payment.AccessProfile profile, @Argument final String clientId,
                                                       @Argument final String role) {
        final nu.fgv.membership.server.payment.AccessProfile kind = profile == null ? nu.fgv.membership.server.payment.AccessProfile.MEMBER : profile;
        final DefaultAccessService.Entry entry = service.set(kind, clientId, role);

        audit.log(caller.getSubject(), "DEFAULT_ACCESS_SET", clientId, kind + ": " + role);
        return entry;
    }
}
