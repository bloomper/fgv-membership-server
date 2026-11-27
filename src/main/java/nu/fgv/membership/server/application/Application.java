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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An application for membership from someone whose account is not linked to a spexare.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "application")
public class Application {

    public enum Status { PENDING, ACCEPTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "applicant_name", nullable = false, length = 200)
    private String applicantName;

    @Column(nullable = false, length = 1000)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_message", length = 500)
    private String decisionMessage;

    @Column(name = "spexare_id", length = 36)
    private String spexareId;

    protected Application() {
    }

    public Application(final String userId, final String applicantName, final String text, final Instant now) {
        this.userId = userId;
        this.applicantName = applicantName;
        this.text = text;
        this.createdAt = now;
    }

    public void accept(final String actor, final String spexareId, final Instant now) {
        this.status = Status.ACCEPTED;
        this.decidedBy = actor;
        this.decidedAt = now;
        this.spexareId = spexareId;
    }

    public void reject(final String actor, final String message, final Instant now) {
        this.status = Status.REJECTED;
        this.decidedBy = actor;
        this.decidedAt = now;
        this.decisionMessage = message;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public String getText() {
        return text;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionMessage() {
        return decisionMessage;
    }

    public String getSpexareId() {
        return spexareId;
    }
}
