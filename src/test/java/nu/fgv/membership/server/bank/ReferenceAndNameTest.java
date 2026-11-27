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

import nu.fgv.membership.server.payment.PaymentReference;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceAndNameTest {

    private final String valid = PaymentReference.generate(Year.of(2027));

    @Test
    void aValidReferenceIsFoundInATextEvenWhenBrokenUp() {
        final String broken = "Swish från Greta " + valid.substring(0, 5) + " " + valid.substring(5, 8) + "-" + valid.substring(8).toLowerCase();

        assertThat(ReferenceFinder.valid(broken)).containsExactly(valid);
        assertThat(ReferenceFinder.valid("Medlemsavgift")).isEmpty();
        assertThat(ReferenceFinder.valid(null)).isEmpty();
    }

    @Test
    void aReferenceWithAWrongCheckCharacterIsKeptApartAndItsNeighboursAreFound() {
        final char wrong = valid.charAt(8) == 'A' ? 'B' : 'A';
        final String typo = valid.substring(0, 8) + wrong;

        assertThat(ReferenceFinder.valid(typo)).isEmpty();
        assertThat(ReferenceFinder.invalid("betalning " + typo)).containsExactly(typo);
        assertThat(ReferenceFinder.nearest(typo, List.of(valid, "FGV27ZZZZZ"))).containsExactly(valid);
    }

    @Test
    void namesAreComparedWithoutCaseOrDiacritics() {
        assertThat(NameMatcher.similarity("GRETA GARBO", "Greta Garbo")).isEqualTo(1.0);
        assertThat(NameMatcher.similarity("Björn Ödman Swish", "Bjorn Odman")).isEqualTo(1.0);
        assertThat(NameMatcher.similarity("Garbo G", "Greta Garbo")).isEqualTo(0.5);
        assertThat(NameMatcher.similarity("Ingrid Bergman", "Greta Garbo")).isZero();
        assertThat(NameMatcher.similarity(null, "Greta Garbo")).isZero();
    }
}
