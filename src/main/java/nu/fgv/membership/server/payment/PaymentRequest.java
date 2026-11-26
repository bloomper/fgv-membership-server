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

package nu.fgv.membership.server.payment;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A request to pay for one or more memberships. Its reference travels with the payment.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "payment_request")
public class PaymentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 12, unique = true)
    private String reference;

    @Column(name = "spexare_id", length = 36)
    private String spexareId;

    @Column(name = "payer_label", length = 200)
    private String payerLabel;

    @Column(name = "family_id", length = 36)
    private String familyId;

    @Column(name = "family_note", length = 500)
    private String familyNote;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.OPEN;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "paid_by", length = 200)
    private String paidBy;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @ElementCollection
    @CollectionTable(name = "payment_request_membership", joinColumns = @JoinColumn(name = "request_pk"))
    @Column(name = "membership_id", length = 36)
    private Set<String> membershipIds = new LinkedHashSet<>();

    protected PaymentRequest() {
    }

    public PaymentRequest(final String reference, final String spexareId, final int amount, final String createdBy,
                          final Set<String> membershipIds, final Instant now) {
        this.reference = reference;
        this.spexareId = spexareId;
        this.amount = amount;
        this.createdBy = createdBy;
        this.membershipIds = new LinkedHashSet<>(membershipIds);
        this.createdAt = now;
    }

    /**
     * A request that a revival pays for several participants: it has no single spexare, and the revival is its payer.
     */
    public static PaymentRequest forRevival(final String reference, final String payerLabel, final int amount, final String createdBy,
                                            final Set<String> membershipIds, final Instant now) {
        final PaymentRequest request = new PaymentRequest(reference, null, amount, createdBy, membershipIds, now);

        request.payerLabel = payerLabel;
        return request;
    }

    /**
     * A family membership: the memberships of the family share this id, and the note says who the payer said they are.
     */
    public void family(final String familyId, final String note) {
        this.familyId = familyId;
        this.familyNote = note;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getFamilyNote() {
        return familyNote;
    }

    public boolean isForRevival() {
        return payerLabel != null;
    }

    public String getPayerLabel() {
        return payerLabel;
    }

    public void report(final String paidBy, final Instant now) {
        this.status = PaymentStatus.REPORTED;
        this.reportedAt = now;
        this.paidBy = paidBy;
    }

    public void matched() {
        this.status = PaymentStatus.MATCHED;
    }

    /**
     * Back to what it was before the payment: reported if the payer had said so.
     */
    public void reopen() {
        this.status = reportedAt != null ? PaymentStatus.REPORTED : PaymentStatus.OPEN;
    }

    public void cancel() {
        this.status = PaymentStatus.CANCELLED;
    }

    public void reminded(final Instant now) {
        this.reminderSentAt = now;
    }

    public boolean isPayable() {
        return status == PaymentStatus.OPEN || status == PaymentStatus.REPORTED;
    }

    public Long getPk() {
        return pk;
    }

    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getSpexareId() {
        return spexareId;
    }

    public int getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public Instant getReminderSentAt() {
        return reminderSentAt;
    }

    public Set<String> getMembershipIds() {
        return membershipIds;
    }
}
