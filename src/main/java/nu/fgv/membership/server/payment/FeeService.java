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

import nu.fgv.membership.server.membership.Membership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Fee levels per type and year, kept by admins.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class FeeService {

    private final FeeLevelRepository fees;
    private final Clock clock;

    public FeeService(final FeeLevelRepository fees, final Clock clock) {
        this.fees = fees;
        this.clock = clock;
    }

    /**
     * @return the levels members may pick: not retired, for the year or for life; highest amount first
     */
    @Transactional(readOnly = true)
    public List<FeeLevel> available(final Integer year) {
        return fees.findByRetiredFalseOrderByAmountDesc().stream()
                .filter(level -> year == null || level.isLifetime() || year.equals(level.getYear())).toList();
    }

    @Transactional(readOnly = true)
    public List<FeeLevel> all() {
        return fees.findAllByOrderByYearDescNameSvAsc();
    }

    @Transactional
    public FeeLevel save(final String id, final Integer year, final boolean lifetime, final String nameSv, final String nameEn, final int amount) {
        if (nameSv == null || nameSv.isBlank() || nameEn == null || nameEn.isBlank()) {
            throw new IllegalArgumentException("A fee level needs a name in Swedish and in English");
        }
        if (id == null) {
            return fees.save(new FeeLevel(Membership.FGV, year, lifetime, nameSv.strip(), nameEn.strip(), amount, clock.instant()));
        }

        final FeeLevel level = fees.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown fee level " + id));

        level.update(year, lifetime, nameSv.strip(), nameEn.strip(), amount);
        return level;
    }

    @Transactional
    public FeeLevel retire(final String id, final boolean retired) {
        final FeeLevel level = fees.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown fee level " + id));

        level.setRetired(retired);
        return level;
    }
}
