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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CsvAndParserTest {

    private static BankFormat format(final boolean decimalComma, final String dateFormat, final Integer payer) {
        final BankFormat format = new BankFormat(Instant.now());

        format.update("Test", "UTF-8", ";", 1, 1, 2, 3, payer, dateFormat, decimalComma);
        return format;
    }

    @Test
    void quotedFieldsDoubledQuotesAndLineBreaksInsideQuotesAreRead() {
        final var rows = Csv.parse("a;\"b;c\";\"d \"\"e\"\"\nf\"\r\n\r\nx;y;z", ';');

        assertThat(rows).containsExactly(java.util.List.of("a", "b;c", "d \"e\"\nf"), java.util.List.of("x", "y", "z"));
    }

    @Test
    void aByteOrderMarkAndAMissingFinalNewlineAreHandled() {
        assertThat(Csv.parse("\uFEFFa;b\n1;2", ';')).containsExactly(java.util.List.of("a", "b"), java.util.List.of("1", "2"));
    }

    @Test
    void swedishAmountsWithSpacesAndDecimalCommasAreRead() {
        assertThat(StatementParser.ore("1 200,00", true)).isEqualTo(120_000);
        assertThat(StatementParser.ore("1\u00A0200,5", true)).isEqualTo(120_050);
        assertThat(StatementParser.ore("-200,00", true)).isEqualTo(-20_000);
        assertThat(StatementParser.ore("\u2212200", true)).isEqualTo(-20_000);
        assertThat(StatementParser.ore("1,200.50", false)).isEqualTo(120_050);
        assertThat(StatementParser.ore("200 kr", true)).isEqualTo(20_000);
    }

    @Test
    void aFileIsReadRowByRowAfterTheHeader() {
        final var result = StatementParser.parse("Datum;Belopp;Text;Namn\n2026-11-02;200,00;FGV27K7QX;Greta Garbo\n2026-11-03;-50,00;Avgift;", format(true, "yyyy-MM-dd", 4));

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).containsExactly(new StatementParser.Row(LocalDate.of(2026, 11, 2), 20_000, "FGV27K7QX", "Greta Garbo"),
                new StatementParser.Row(LocalDate.of(2026, 11, 3), -5_000, "Avgift", ""));
    }

    @Test
    void aBadRowIsNamedAndNothingIsReturned() {
        final var result = StatementParser.parse("h\n2026-11-02;200;ok\nnot a date;200;x\n2026-11-04;abc;y\n2026-11-05;1", format(true, "yyyy-MM-dd", null));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(3);
        assertThat(result.errors().getFirst()).startsWith("Row 3:");
    }

    @Test
    void otherDateFormatsAreDescribedInTheFormat() {
        final var result = StatementParser.parse("h\n02/11/2026;200;x", format(true, "dd/MM/yyyy", null));

        assertThat(result.rows().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 2));
    }
}
