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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A payment another system told us about, kept by that system's reference so that telling again changes nothing.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "external_payment")
public class ExternalPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(name = "source_user", nullable = false, length = 36)
    private String sourceUser;

    @Column(length = 100)
    private String source;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "account_created", nullable = false)
    private boolean accountCreated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExternalPayment() {
    }

    public ExternalPayment(final String sourceUser, final String source, final String externalRef, final String requestId, final String status,
                           final boolean accountCreated, final Instant now) {
        this.sourceUser = sourceUser;
        this.source = source;
        this.externalRef = externalRef;
        this.requestId = requestId;
        this.status = status;
        this.accountCreated = accountCreated;
        this.createdAt = now;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getStatus() {
        return status;
    }

    public boolean isAccountCreated() {
        return accountCreated;
    }
}
