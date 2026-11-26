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

import nu.fgv.membership.server.payment.PaymentService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Bank statements for the treasurer, and the bank formats for admins.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class BankGraphqlApi {

    private static final String TREASURER = "hasRole('fgv-membership_TREASURER')";
    private static final String ADMIN = "hasRole('fgv-membership_ADMIN')";

    private final BankService bank;
    private final PaymentService payments;
    private final BankFormatRepository formats;
    private final Clock clock;

    public BankGraphqlApi(final BankService bank, final PaymentService payments, final BankFormatRepository formats, final Clock clock) {
        this.bank = bank;
        this.payments = payments;
        this.formats = formats;
        this.clock = clock;
    }

    public record TransactionView(String id, String bookedOn, int amount, String text, String payer, String status, String fileName) {
    }

    public record FormatInput(String id, String name, String charset, String delimiter, int headerLines, int dateColumn, int amountColumn,
                              int textColumn, Integer payerColumn, String dateFormat, boolean decimalComma) {
    }

    public record FormatView(String id, String name, String charset, String delimiter, int headerLines, int dateColumn, int amountColumn,
                             int textColumn, Integer payerColumn, String dateFormat, boolean decimalComma) {
    }

    @QueryMapping
    @PreAuthorize(TREASURER)
    @Transactional(readOnly = true)
    public List<FormatView> bankFormats() {
        return formats.findAllByOrderByNameAsc().stream().map(BankGraphqlApi::view).toList();
    }

    @QueryMapping
    @PreAuthorize(TREASURER)
    public List<TransactionView> unmatchedTransactions() {
        return bank.unmatched().stream().map(BankGraphqlApi::view).toList();
    }

    @QueryMapping
    @PreAuthorize(TREASURER)
    public List<BankService.Suggestion> transactionSuggestions(@Argument final String id) {
        return bank.suggestions(id);
    }

    @QueryMapping
    @PreAuthorize(TREASURER)
    public List<PaymentService.PaymentLine> paymentsThisYear() {
        return payments.thisYearsPayments();
    }

    @MutationMapping
    @PreAuthorize(TREASURER)
    public BankService.ImportResult importStatement(@AuthenticationPrincipal final Jwt caller, @Argument final String formatId,
                                                    @Argument final String fileName, @Argument final String content) {
        return bank.importStatement(caller.getSubject(), formatId, fileName, content);
    }

    @MutationMapping
    @PreAuthorize(TREASURER)
    public TransactionView matchTransaction(@AuthenticationPrincipal final Jwt caller, @Argument final String id, @Argument final List<String> requestIds) {
        return view(bank.match(caller.getSubject(), id, requestIds));
    }

    @MutationMapping
    @PreAuthorize(TREASURER)
    public TransactionView dismissTransaction(@Argument final String id) {
        return view(bank.dismiss(id));
    }

    @MutationMapping
    @PreAuthorize(TREASURER)
    public boolean undoPayment(@Argument final String id) {
        payments.undoPayment(id);
        return true;
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    @Transactional
    public FormatView bankFormatSave(@Argument final FormatInput input) {
        final BankFormat format = input.id() == null ? new BankFormat(clock.instant()) : formats.findById(input.id())
                .orElseThrow(() -> new IllegalArgumentException("Unknown format"));

        format.update(input.name(), input.charset(), input.delimiter(), input.headerLines(), input.dateColumn(), input.amountColumn(),
                input.textColumn(), input.payerColumn(), input.dateFormat(), input.decimalComma());
        return view(formats.save(format));
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    @Transactional
    public boolean bankFormatDelete(@Argument final String id) {
        formats.findById(id).ifPresent(formats::delete);
        return true;
    }

    private static TransactionView view(final BankTransaction t) {
        return new TransactionView(t.getId(), t.getBookedOn().toString(), t.kronor(), t.getText(), t.getPayer(), t.getStatus().name(), t.getFileName());
    }

    private static FormatView view(final BankFormat f) {
        return new FormatView(f.getId(), f.getName(), f.getCharset(), f.getDelimiter(), f.getHeaderLines(), f.getDateColumn(), f.getAmountColumn(),
                f.getTextColumn(), f.getPayerColumn(), f.getDateFormat(), f.isDecimalComma());
    }
}
