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

package nu.fgv.membership.server.membership;

import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class MembershipFeedApi {

    private final MembershipFeed feed;

    public MembershipFeedApi(final MembershipFeed feed) {
        this.feed = feed;
    }

    @QueryMapping
    @PreAuthorize("hasRole('fgv-membership_SYNC')")
    public MembershipFeed.Page membershipChanges(@AuthenticationPrincipal final Jwt caller, @Argument final @Nullable String after,
                                                 @Argument final @Nullable Integer first) {
        return feed.read(caller.getClaimAsString("azp"), after, first);
    }

    @SchemaMapping(typeName = "MembershipChange")
    public String sequence(final MembershipChange change) {
        return Long.toString(change.getSequence());
    }

    @SchemaMapping(typeName = "MembershipChange")
    public String id(final MembershipChange change) {
        return change.getMembershipId();
    }

    @SchemaMapping(typeName = "MembershipChange")
    public String at(final MembershipChange change) {
        return change.getAt().toString();
    }
}
