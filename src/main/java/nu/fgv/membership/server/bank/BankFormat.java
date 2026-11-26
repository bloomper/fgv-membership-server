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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * How a bank's CSV export looks: which columns hold the date, the amount, the text and the payer. A change of bank is a
 * new format, not code. Columns are counted from 1.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "bank_format")
public class BankFormat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long pk;

    @Column(nullable = false, length = 36, unique = true)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(nullable = false, length = 30)
    private String charset = "UTF-8";

    @Column(nullable = false, length = 1)
    private String delimiter = ";";

    @Column(name = "header_lines", nullable = false)
    private int headerLines = 1;

    @Column(name = "date_column", nullable = false)
    private int dateColumn;

    @Column(name = "amount_column", nullable = false)
    private int amountColumn;

    @Column(name = "text_column", nullable = false)
    private int textColumn;

    @Column(name = "payer_column")
    private @Nullable Integer payerColumn;

    @Column(name = "date_format", nullable = false, length = 30)
    private String dateFormat = "yyyy-MM-dd";

    @Column(name = "decimal_comma", nullable = false)
    private boolean decimalComma = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BankFormat() {
    }

    public BankFormat(final Instant now) {
        this.createdAt = now;
    }

    @SuppressWarnings("java:S107")
    public void update(final String name, final String charset, final String delimiter, final int headerLines, final int dateColumn,
                       final int amountColumn, final int textColumn, final @Nullable Integer payerColumn, final String dateFormat,
                       final boolean decimalComma) {
        if (delimiter.length() != 1 || name.isBlank() || dateColumn < 1 || amountColumn < 1 || textColumn < 1 || headerLines < 0
                || (payerColumn != null && payerColumn < 1)) {
            throw new IllegalArgumentException("The format needs a name, a one-character delimiter and columns counted from 1");
        }
        java.nio.charset.Charset.forName(charset);
        java.time.format.DateTimeFormatter.ofPattern(dateFormat);
        this.name = name.strip();
        this.charset = charset;
        this.delimiter = delimiter;
        this.headerLines = headerLines;
        this.dateColumn = dateColumn;
        this.amountColumn = amountColumn;
        this.textColumn = textColumn;
        this.payerColumn = payerColumn;
        this.dateFormat = dateFormat;
        this.decimalComma = decimalComma;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCharset() {
        return charset;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public int getHeaderLines() {
        return headerLines;
    }

    public int getDateColumn() {
        return dateColumn;
    }

    public int getAmountColumn() {
        return amountColumn;
    }

    public int getTextColumn() {
        return textColumn;
    }

    public @Nullable Integer getPayerColumn() {
        return payerColumn;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public boolean isDecimalComma() {
        return decimalComma;
    }
}
