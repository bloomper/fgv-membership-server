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

import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReferenceTest {

    @Test
    void aGeneratedReferenceIsValidAndShaped() {
        for (int i = 0; i < 500; i++) {
            final String reference = PaymentReference.generate(Year.of(2027));

            assertThat(reference).matches("FGV27[2-9A-HJ-NP-Z]{4}");
            assertThat(PaymentReference.isValid(reference)).isTrue();
        }
    }

    @Test
    void theAlphabetHasNoLookAlikes() {
        assertThat(PaymentReference.ALPHABET).doesNotContain("0", "O", "1", "I").hasSize(32);
    }

    @Test
    void anyChangeOfASingleCharacterIsCaught() {
        final String body = "FGV27K7Q";
        final String reference = body + PaymentReference.check(body);

        for (int position = 5; position < 9; position++) {
            for (final char other : PaymentReference.ALPHABET.toCharArray()) {
                if (other != reference.charAt(position)) {
                    final String typo = reference.substring(0, position) + other + reference.substring(position + 1);

                    assertThat(PaymentReference.isValid(typo)).as(typo).isFalse();
                }
            }
        }
    }

    @Test
    void aTypoInTheMiddleFailsTheCheck() {
        final String body = "FGV27K7Q";
        final String reference = body + PaymentReference.check(body);

        assertThat(PaymentReference.isValid(reference)).isTrue();
        assertThat(PaymentReference.isValid("FGV27K8Q" + reference.charAt(8))).isFalse();
        assertThat(PaymentReference.isValid("FGV27" + "7K" + "Q" + reference.charAt(8))).isFalse();
    }

    @Test
    void caseSpacesAndHyphensAreIgnored() {
        final String body = "FGV27K7Q";
        final String reference = body + PaymentReference.check(body);

        assertThat(PaymentReference.isValid(reference.toLowerCase())).isTrue();
        assertThat(PaymentReference.isValid("FGV 27 " + reference.substring(5, 8) + "-" + reference.charAt(8))).isTrue();
        assertThat(PaymentReference.normalize("not a reference")).isNull();
        assertThat(PaymentReference.normalize("FGV27K0QX")).isNull();
    }
}
