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

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Spexregister tells that a spexare was deleted there. Medlemskap keeps the memberships and payments, which are
 * bookkeeping records, and marks them as belonging to a deleted spexare. Needs {@code SYNC}, like the feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class SpexareDeletedController {

    private final MembershipService service;

    public SpexareDeletedController(final MembershipService service) {
        this.service = service;
    }

    public record Deleted(UUID spexareId) {
    }

    @PostMapping("/api/sync/spexare-deleted")
    @PreAuthorize("hasRole('fgv-membership_SYNC')")
    public ResponseEntity<Void> spexareDeleted(@RequestBody final Deleted deleted) {
        service.spexareDeleted(deleted.spexareId().toString());
        return ResponseEntity.noContent().build();
    }
}
