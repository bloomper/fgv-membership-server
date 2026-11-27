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

/**
 * One line of the feed. The record is as it was right after the change, so applying the lines in order gives the
 * same copy whenever it is read. The primary key is the feed's sequence.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "membership_change")
public class MembershipChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;

    @Column(name = "membership_id", nullable = false, length = 36)
    private String membershipId;

    @Column(name = "spexare_id", nullable = false, length = 36)
    private String spexareId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "membership_year", nullable = false)
    private int year;

    @Column(nullable = false)
    private boolean lifetime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChangeKind kind;

    @Column(nullable = false)
    private Instant at;

    protected MembershipChange() {
    }

    public MembershipChange(final Membership membership, final ChangeKind kind, final Instant at) {
        this.membershipId = membership.getId();
        this.spexareId = membership.getSpexareId();
        this.type = membership.getType();
        this.year = membership.getYear();
        this.lifetime = membership.isLifetime();
        this.kind = kind;
        this.at = at;
    }

    public Long getSequence() {
        return sequence;
    }

    public String getMembershipId() {
        return membershipId;
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

    public ChangeKind getKind() {
        return kind;
    }

    public Instant getAt() {
        return at;
    }
}
