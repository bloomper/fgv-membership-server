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

package nu.fgv.membership.server.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * What a source key made: the membership, and its fingerprint right after the import.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "import_key")
public class ImportKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_key", nullable = false, updatable = false)
    private String sourceKey;

    @Column(name = "source_hash", nullable = false, updatable = false)
    private String sourceHash;

    @Column(name = "record_kind", nullable = false, updatable = false)
    private String recordKind;

    @Column(name = "record_id", nullable = false)
    private String recordId;

    @Column(name = "imported_hash", nullable = false)
    private String importedHash;

    @Column(name = "record_version", nullable = false)
    private long recordVersion;

    @Column(name = "imported_by", nullable = false)
    private String importedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportKey() {
    }

    ImportKey(final String sourceKey, final String sourceHash, final String recordKind, final Instant now) {
        this.sourceKey = sourceKey;
        this.sourceHash = sourceHash;
        this.recordKind = recordKind;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void imported(final String recordId, final String importedHash, final long recordVersion, final String importedBy, final Instant now) {
        this.recordId = recordId;
        this.importedHash = importedHash;
        this.recordVersion = recordVersion;
        this.importedBy = importedBy;
        this.updatedAt = now;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getRecordId() {
        return recordId;
    }

    public String getImportedHash() {
        return importedHash;
    }

    public long getRecordVersion() {
        return recordVersion;
    }
}
