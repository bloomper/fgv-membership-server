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

import nu.fgv.membership.server.board.AuditService;
import nu.fgv.membership.server.membership.Membership;
import nu.fgv.membership.server.membership.MembershipHow;
import nu.fgv.membership.server.membership.MembershipRepository;
import nu.fgv.membership.server.membership.MembershipService;
import nu.fgv.membership.server.membership.MembershipStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spexregister 1.x's FGV memberships, carried in once by {@code spexregister-migration} (spec 5.9): each becomes
 * {@code PAID}, how {@code MIGRATED}, through {@link MembershipService#record}, so that its line in the feed tells
 * Spexregister. Source keys make a second run update or leave alone what the first made: a membership the board has
 * changed since, or one the spexare already had, is {@code KEPT}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MembershipImport {

    private static final Logger log = LoggerFactory.getLogger(MembershipImport.class);

    public static final int MAX_RECORDS = 500;
    static final String KIND = "MEMBERSHIP";
    static final String NOTE = "Migrated from Spexregister";

    private final ImportKeyRepository keys;
    private final MembershipRepository memberships;
    private final MembershipService service;
    private final AuditService audit;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public MembershipImport(final ImportKeyRepository keys, final MembershipRepository memberships, final MembershipService service,
                            final AuditService audit, final PlatformTransactionManager transactionManager, final Clock clock) {
        this.keys = keys;
        this.memberships = memberships;
        this.service = service;
        this.audit = audit;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** One FGV membership: the spexare by Spexregister's id, and the year. */
    public record Record(String sourceKey, String spexareId, int year) {
    }

    public enum Outcome {
        CREATED, UPDATED, UNCHANGED, KEPT, FAILED
    }

    public record Item(String kind, String sourceKey, String id, Outcome outcome, String message, List<String> warnings) {
    }

    /** Each record in its own transaction; a dry run rolls every one back. */
    public List<Item> run(final List<Record> records, final boolean dryRun, final String actor) {
        if (records.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("At most " + MAX_RECORDS + " records per call");
        }

        final List<Item> items = new ArrayList<>();

        for (final Record record : records) {
            final String sourceKey = Objects.requireNonNullElse(record.sourceKey(), "").strip();

            try {
                items.add(Objects.requireNonNull(transactionTemplate.execute(status -> {
                    final Item item = one(record, sourceKey, actor);

                    if (dryRun) {
                        status.setRollbackOnly();
                    }
                    return item;
                })));
            } catch (final RuntimeException e) {
                log.warn("Import of membership {} failed", sourceKey, e);
                items.add(new Item(KIND, sourceKey, null, Outcome.FAILED, e.getMessage(), List.of()));
            }
        }
        return items;
    }

    private Item one(final Record record, final String sourceKey, final String actor) {
        if (sourceKey.isEmpty() || sourceKey.length() > 1000) {
            return new Item(KIND, sourceKey, null, Outcome.FAILED, "The record has no source key, or one longer than 1000 characters", List.of());
        }

        final String spexareId;

        try {
            spexareId = UUID.fromString(record.spexareId().strip()).toString();
        } catch (final RuntimeException e) {
            return new Item(KIND, sourceKey, null, Outcome.FAILED, "The spexare is not a Spexregister id", List.of());
        }
        if (record.year() < 1900 || record.year() > 2200) {
            return new Item(KIND, sourceKey, null, Outcome.FAILED, "The year is not a year", List.of());
        }

        final String sourceHash = sha256(sourceKey);
        final String contentHash = sha256(spexareId + "|" + record.year());
        final Optional<ImportKey> key = keys.findBySourceHash(sourceHash);

        if (key.isPresent()) {
            final Optional<Membership> made = memberships.findById(key.get().getRecordId());

            if (made.isEmpty() || fingerprint(made.get()) != key.get().getRecordVersion()) {
                return new Item(KIND, sourceKey, key.get().getRecordId(), Outcome.KEPT, null, List.of());
            }
            if (contentHash.equals(key.get().getImportedHash())) {
                return new Item(KIND, sourceKey, key.get().getRecordId(), Outcome.UNCHANGED, null, List.of());
            }
            // Another spexare or year than before: the old one goes, as it was a mistake
            service.delete(made.get().getId());
            audit.log(actor, "MIGRATION_REMOVED", made.get().getSpexareId(), "FGV " + made.get().getYear());
        }

        final Optional<Membership> existing = memberships.findBySpexareIdAndTypeAndYear(spexareId, Membership.FGV, record.year());

        if (existing.isPresent()) {
            return new Item(KIND, sourceKey, existing.get().getId(), Outcome.KEPT, null,
                    List.of("The spexare already has a membership for " + record.year()));
        }

        final Membership membership = service.record(spexareId, Membership.FGV, record.year(), false, MembershipStatus.PAID, MembershipHow.MIGRATED, NOTE);
        final ImportKey saved = key.orElseGet(() -> new ImportKey(sourceKey, sourceHash, KIND, clock.instant()));

        audit.log(actor, "MIGRATED", spexareId, "FGV " + record.year());
        saved.imported(membership.getId(), contentHash, fingerprint(membership), actor, clock.instant());
        keys.save(saved);
        return new Item(KIND, sourceKey, membership.getId(), key.isPresent() ? Outcome.UPDATED : Outcome.CREATED, null, List.of());
    }

    /** Removes what was imported under the prefix and is still as imported; returns how many. */
    public int forget(final String prefix, final String actor) {
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("Give a source prefix");
        }

        int removed = 0;

        for (final ImportKey key : keys.findBySourcePrefix(prefix.strip())) {
            final Boolean done = transactionTemplate.execute(_ -> {
                final Optional<Membership> made = memberships.findById(key.getRecordId());

                if (made.isEmpty()) {
                    keys.delete(key);
                    return false;
                }
                if (fingerprint(made.get()) != key.getRecordVersion()) {
                    return false;
                }
                service.delete(made.get().getId());
                audit.log(actor, "MIGRATION_REMOVED", made.get().getSpexareId(), "FGV " + made.get().getYear());
                keys.delete(key);
                return true;
            });

            if (Boolean.TRUE.equals(done)) {
                removed++;
            }
        }
        return removed;
    }

    /** What the board could change: status, how, life membership, note, the spexare being deleted. */
    static long fingerprint(final Membership m) {
        final byte[] digest = sha256Bytes(String.join("|", m.getSpexareId(), m.getType(), Integer.toString(m.getYear()),
                Boolean.toString(m.isLifetime()), String.valueOf(m.getStatus()), String.valueOf(m.getHow()),
                Objects.requireNonNullElse(m.getNote(), ""), Boolean.toString(m.isSpexareDeleted())));
        long value = 0;

        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (digest[i] & 0xFF);
        }
        return value;
    }

    private static String sha256(final String text) {
        return HexFormat.of().formatHex(sha256Bytes(text));
    }

    private static byte[] sha256Bytes(final String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
