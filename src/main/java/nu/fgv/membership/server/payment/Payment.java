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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Money that arrived for a payment request.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(name = "request_pk", nullable = false)
    private Long requestPk;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentHow how;

    @Column(name = "recorded_by", nullable = false, length = 36)
    private String recordedBy;

    @Column(length = 500)
    private String note;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "transaction_pk")
    private Long transactionPk;

    protected Payment() {
    }

    public Payment(final Long requestPk, final LocalDate paidOn, final int amount, final PaymentHow how, final String recordedBy,
                   final String note, final Instant now) {
        this.requestPk = requestPk;
        this.paidOn = paidOn;
        this.amount = amount;
        this.how = how;
        this.recordedBy = recordedBy;
        this.note = note;
        this.recordedAt = now;
    }

    public Payment fromTransaction(final Long transactionPk) {
        this.transactionPk = transactionPk;
        return this;
    }

    public void linkTransaction(final Long transactionPk) {
        this.transactionPk = transactionPk;
    }

    public Long getPk() {
        return pk;
    }

    public Long getTransactionPk() {
        return transactionPk;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getId() {
        return id;
    }

    public Long getRequestPk() {
        return requestPk;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public int getAmount() {
        return amount;
    }

    public PaymentHow getHow() {
        return how;
    }

    public String getNote() {
        return note;
    }
}
