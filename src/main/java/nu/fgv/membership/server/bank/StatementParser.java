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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a bank's CSV export in a described format. Everything or nothing: when some rows cannot be read the errors say
 * which, and nothing is returned for import.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class StatementParser {

    private StatementParser() {
    }

    /**
     * @param payer the payer's name as the bank gives it, or null
     * @param ore   negative for outgoing
     */
    public record Row(LocalDate date, long ore, String text, String payer) {
    }

    /**
     * @param errors "Row 12: ..." for each row that could not be read, at most 20
     */
    public record Result(List<Row> rows, List<String> errors) {
    }

    public static Result parse(final String text, final BankFormat format) {
        final List<List<String>> lines = Csv.parse(text, format.getDelimiter().charAt(0));
        final DateTimeFormatter dates = DateTimeFormatter.ofPattern(format.getDateFormat());
        final List<Row> rows = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        for (int i = format.getHeaderLines(); i < lines.size(); i++) {
            final List<String> columns = lines.get(i);

            try {
                rows.add(new Row(LocalDate.parse(column(columns, format.getDateColumn()).strip(), dates),
                        ore(column(columns, format.getAmountColumn()), format.isDecimalComma()),
                        column(columns, format.getTextColumn()).strip(),
                        format.getPayerColumn() == null ? null : column(columns, format.getPayerColumn()).strip()));
            } catch (RuntimeException e) {
                if (errors.size() < 20) {
                    errors.add("Row %d: %s".formatted(i + 1, e.getMessage()));
                }
            }
        }
        return new Result(errors.isEmpty() ? rows : List.of(), errors);
    }

    private static String column(final List<String> columns, final int number) {
        if (number > columns.size()) {
            throw new IllegalArgumentException("there is no column " + number);
        }
        return columns.get(number - 1);
    }

    /**
     * @return the amount in öre; spaces and non-breaking spaces are thousands separators, and the unicode minus is a minus
     */
    static long ore(final String text, final boolean decimalComma) {
        String value = text.replace('\u2212', '-').replaceAll("[\\s\u00A0\u202F]", "").replace("kr", "");

        if (value.isEmpty()) {
            throw new IllegalArgumentException("the amount is empty");
        }
        value = decimalComma ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
        try {
            return new BigDecimal(value).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("'" + text.strip() + "' is not an amount");
        }
    }
}
