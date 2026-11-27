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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A spexare's membership for one calendar year, or a life membership from a first year onwards. The spexare is a
 * reference to Spexregister; names and contact details are never stored here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "membership")
public class Membership {

    public static final String FGV = "FGV";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(name = "spexare_id", nullable = false, length = 36)
    private String spexareId;

    @Column(nullable = false, length = 20)
    private String type = FGV;

    @Column(name = "membership_year", nullable = false)
    private int year;

    @Column(nullable = false)
    private boolean lifetime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MembershipHow how;

    @Column(length = 500)
    private String note;

    @Column(name = "family_id", length = 36)
    private String familyId;

    @Column(nullable = false)
    private boolean support;

    @Column(name = "spexare_deleted", nullable = false)
    private boolean spexareDeleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Membership() {
    }

    public Membership(final String spexareId, final String type, final int year, final Instant now) {
        this.spexareId = spexareId;
        this.type = type;
        this.year = year;
        this.status = MembershipStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean counts() {
        return status.counts();
    }

    public void update(final boolean lifetime, final MembershipStatus status, final MembershipHow how, final String note,
                       final Instant now) {
        this.lifetime = lifetime;
        this.status = status;
        this.how = how;
        this.note = note;
        this.updatedAt = now;
    }

    public void markSpexareDeleted(final Instant now) {
        this.spexareDeleted = true;
        this.updatedAt = now;
    }

    public void setSupport(final boolean support) {
        this.support = support;
    }

    public boolean isSupport() {
        return support;
    }

    public void setFamilyId(final String familyId) {
        this.familyId = familyId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public Long getPk() {
        return pk;
    }

    public String getId() {
        return id;
    }

    public String getSpexareId() {
        return spexareId;
    }

    public String getType() {
        return type;
    }

    public int getYear() {
        return year;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public MembershipHow getHow() {
        return how;
    }

    public String getNote() {
        return note;
    }

    public boolean isSpexareDeleted() {
        return spexareDeleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
