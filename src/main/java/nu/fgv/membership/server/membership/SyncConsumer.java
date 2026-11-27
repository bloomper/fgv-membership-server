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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A consumer of the feed, known by its service account, and how far it has read.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "sync_consumer")
public class SyncConsumer {

    @Id
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(nullable = false)
    private long acknowledged;

    @Column(name = "acknowledged_at", nullable = false)
    private Instant acknowledgedAt;

    protected SyncConsumer() {
    }

    public SyncConsumer(final String clientId, final long acknowledged, final Instant at) {
        this.clientId = clientId;
        this.acknowledged = acknowledged;
        this.acknowledgedAt = at;
    }

    public void acknowledge(final long sequence, final Instant at) {
        if (sequence > acknowledged) {
            this.acknowledged = sequence;
        }
        this.acknowledgedAt = at;
    }

    public String getClientId() {
        return clientId;
    }

    public long getAcknowledged() {
        return acknowledged;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }
}
