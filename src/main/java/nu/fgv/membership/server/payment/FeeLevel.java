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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What a membership costs for a year, or the one-time fee for a life membership.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "fee_level")
public class FeeLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "fee_year")
    private Integer year;

    @Column(nullable = false)
    private boolean lifetime;

    @Column(name = "name_sv", nullable = false, length = 100)
    private String nameSv;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false)
    private boolean retired;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FeeLevel() {
    }

    public FeeLevel(final String type, final Integer year, final boolean lifetime, final String nameSv, final String nameEn,
                    final int amount, final Instant now) {
        this.type = type;
        this.createdAt = now;
        update(year, lifetime, nameSv, nameEn, amount);
    }

    public final void update(final Integer year, final boolean lifetime, final String nameSv, final String nameEn, final int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("The amount cannot be negative");
        }
        if (!lifetime && year == null) {
            throw new IllegalArgumentException("A yearly fee needs a year");
        }
        this.year = lifetime ? null : year;
        this.lifetime = lifetime;
        this.nameSv = nameSv;
        this.nameEn = nameEn;
        this.amount = amount;
    }

    public void setRetired(final boolean retired) {
        this.retired = retired;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Integer getYear() {
        return year;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public String getNameSv() {
        return nameSv;
    }

    public String getNameEn() {
        return nameEn;
    }

    public int getAmount() {
        return amount;
    }

    public boolean isRetired() {
        return retired;
    }
}
