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

package nu.fgv.membership.server.imports;

import nu.fgv.membership.server.imports.MembershipImport.Item;
import nu.fgv.membership.server.imports.MembershipImport.Outcome;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code importBatch} and {@code importForget} for {@code IMPORT}: the service account of {@code spexregister-migration}
 * (spec 5.9). Audited as the caller's client, {@code azp}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ImportGraphqlApi {

    private final MembershipImport service;

    public ImportGraphqlApi(final MembershipImport service) {
        this.service = service;
    }

    public record Input(List<MembershipImport.Record> memberships) {
    }

    public record Result(boolean dryRun, List<Item> items, int created, int updated, int unchanged, int kept, int failed) {

        static Result of(final boolean dryRun, final List<Item> items) {
            final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);

            items.forEach(item -> counts.merge(item.outcome(), 1, Integer::sum));
            return new Result(dryRun, items, counts.getOrDefault(Outcome.CREATED, 0), counts.getOrDefault(Outcome.UPDATED, 0),
                    counts.getOrDefault(Outcome.UNCHANGED, 0), counts.getOrDefault(Outcome.KEPT, 0), counts.getOrDefault(Outcome.FAILED, 0));
        }
    }

    @MutationMapping
    @PreAuthorize("hasRole('fgv-membership_IMPORT')")
    public Result importBatch(@AuthenticationPrincipal final Jwt caller, @Argument final Input input, @Argument final boolean dryRun) {
        return Result.of(dryRun, service.run(Objects.requireNonNullElse(input.memberships(), List.of()), dryRun, actor(caller)));
    }

    @MutationMapping
    @PreAuthorize("hasRole('fgv-membership_IMPORT')")
    public int importForget(@AuthenticationPrincipal final Jwt caller, @Argument final String sourcePrefix) {
        return service.forget(sourcePrefix, actor(caller));
    }

    private static String actor(final Jwt caller) {
        return Objects.requireNonNullElse(caller.getClaimAsString("azp"), caller.getSubject());
    }
}
