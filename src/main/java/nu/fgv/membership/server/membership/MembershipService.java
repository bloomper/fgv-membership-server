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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The one place memberships are written. Whenever what counts changes, a line is added to the feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MembershipService {

    private final MembershipRepository memberships;
    private final MembershipChangeRepository changes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MembershipService(final MembershipRepository memberships, final MembershipChangeRepository changes,
                             final ApplicationEventPublisher events, final Clock clock) {
        this.memberships = memberships;
        this.changes = changes;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates the membership of a spexare for a year, or updates it when there is one.
     */
    @Transactional
    public Membership record(final String spexareId, final String type, final int year, final boolean lifetime,
                             final MembershipStatus status, final MembershipHow how, final String note) {
        final Instant now = clock.instant();
        final Optional<Membership> existing = memberships.findBySpexareIdAndTypeAndYear(spexareId, type, year);
        final boolean counted = existing.map(Membership::counts).orElse(false);
        final boolean wasLifetime = existing.map(Membership::isLifetime).orElse(false);
        final Membership membership = existing.orElseGet(() -> new Membership(spexareId, type, year, now));

        membership.update(lifetime, status, how, note, now);
        memberships.save(membership);
        if (membership.counts() && (!counted || wasLifetime != lifetime)) {
            changes.save(new MembershipChange(membership, ChangeKind.UPSERT, now));
            events.publishEvent(new MembershipsChanged());
        } else if (counted && !membership.counts()) {
            changes.save(new MembershipChange(membership, ChangeKind.DELETE, now));
            events.publishEvent(new MembershipsChanged());
        }
        return membership;
    }

    /**
     * Moves a membership to another status, e.g. when its payment is reported or matched.
     */
    @Transactional
    public Membership setStatus(final String id, final MembershipStatus status, final MembershipHow how, final String note) {
        final Membership membership = memberships.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown membership " + id));

        return record(membership.getSpexareId(), membership.getType(), membership.getYear(), membership.isLifetime(), status, how, note);
    }

    /**
     * Removes a membership altogether; a mistake, not a refund (that is {@code CANCELLED}).
     */
    @Transactional
    public void delete(final String id) {
        final Membership membership = memberships.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown membership " + id));

        if (membership.counts()) {
            changes.save(new MembershipChange(membership, ChangeKind.DELETE, clock.instant()));
            events.publishEvent(new MembershipsChanged());
        }
        memberships.delete(membership);
    }

    /**
     * Spexregister tells that a spexare was deleted. The memberships are bookkeeping records and stay.
     */
    @Transactional
    public int spexareDeleted(final String spexareId) {
        final var found = memberships.findBySpexareId(spexareId);

        found.forEach(membership -> membership.markSpexareDeleted(clock.instant()));
        return found.size();
    }
}
