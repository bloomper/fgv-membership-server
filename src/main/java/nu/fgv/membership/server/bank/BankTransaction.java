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

package nu.fgv.membership.server.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An incoming transaction from an uploaded statement.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(name = "booked_on", nullable = false)
    private LocalDate bookedOn;

    @Column(name = "amount_ore", nullable = false)
    private long amountOre;

    @Column(length = 500)
    private @Nullable String text;

    @Column(length = 200)
    private @Nullable String payer;

    @Column(nullable = false, length = 64, unique = true)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.UNMATCHED;

    @Column(name = "file_name", length = 200)
    private @Nullable String fileName;

    @Column(name = "imported_by", nullable = false, length = 36)
    private String importedBy;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "dismissed_at")
    private @Nullable Instant dismissedAt;

    protected BankTransaction() {
    }

    public BankTransaction(final LocalDate bookedOn, final long amountOre, final @Nullable String text, final @Nullable String payer,
                           final String fingerprint, final @Nullable String fileName, final String importedBy, final Instant now) {
        this.bookedOn = bookedOn;
        this.amountOre = amountOre;
        this.text = text;
        this.payer = payer;
        this.fingerprint = fingerprint;
        this.fileName = fileName;
        this.importedBy = importedBy;
        this.importedAt = now;
    }

    public void matched() {
        this.status = TransactionStatus.MATCHED;
    }

    /**
     * Not a membership payment: the text and payer go at once, the fingerprint stays.
     */
    public void dismiss(final Instant now) {
        this.status = TransactionStatus.DISMISSED;
        this.text = null;
        this.payer = null;
        this.dismissedAt = now;
    }

    /**
     * @return whole kronor; öre are dropped
     */
    public int kronor() {
        return Math.toIntExact(amountOre / 100);
    }

    public Long getPk() {
        return pk;
    }

    public String getId() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public LocalDate getBookedOn() {
        return bookedOn;
    }

    public long getAmountOre() {
        return amountOre;
    }

    public @Nullable String getText() {
        return text;
    }

    public @Nullable String getPayer() {
        return payer;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public @Nullable String getFileName() {
        return fileName;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
