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

package nu.fgv.membership.server.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Who changed what: waivers, payments recorded without a request, reminders sent.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36)
    private String actor;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(length = 100)
    private String subject;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private Instant at;

    protected AuditEntry() {
    }

    public AuditEntry(final String actor, final String action, final String subject, final String detail, final Instant at) {
        this.actor = actor;
        this.action = action;
        this.subject = subject;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.at = at;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getSubject() {
        return subject;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
