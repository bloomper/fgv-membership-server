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

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * The feed of changes to memberships that count, oldest first, which Spexregister keeps its copy from. A consumer
 * that asks for the lines after a cursor has read up to that cursor, which is remembered (like Spexipedia's feed).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MembershipFeed {

    static final int MAX_PAGE = 500;

    private final MembershipChangeRepository changes;
    private final SyncConsumerRepository consumers;
    private final Clock clock;

    public MembershipFeed(final MembershipChangeRepository changes, final SyncConsumerRepository consumers, final Clock clock) {
        this.changes = changes;
        this.consumers = consumers;
        this.clock = clock;
    }

    /**
     * @param cursor  pass as {@code after} for the next page; the same as {@code after} when there was nothing new
     * @param hasMore whether there are more lines right now
     */
    public record Page(List<MembershipChange> changes, String cursor, boolean hasMore) {
    }

    @Transactional
    public Page read(final String clientId, final String after, final Integer first) {
        final long from = parse(after);
        final int size = first == null ? MAX_PAGE : Math.max(1, Math.min(first, MAX_PAGE));
        final List<MembershipChange> found = changes.findBySequenceGreaterThanOrderBySequence(from, PageRequest.of(0, size));
        final long last = found.isEmpty() ? from : found.getLast().getSequence();

        acknowledge(clientId, from);
        return new Page(found, after == null && found.isEmpty() ? null : Long.toString(last), changes.existsBySequenceGreaterThan(last));
    }

    private void acknowledge(final String clientId, final long sequence) {
        final var consumer = consumers.findById(clientId).orElseGet(() -> new SyncConsumer(clientId, 0, clock.instant()));

        consumer.acknowledge(sequence, clock.instant());
        consumers.save(consumer);
    }

    private static long parse(final String after) {
        if (after == null || after.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(after));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
