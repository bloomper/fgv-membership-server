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

import nu.fgv.membership.server.payment.PaymentHow;
import nu.fgv.membership.server.payment.PaymentRequest;
import nu.fgv.membership.server.payment.PaymentRequestRepository;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentStatus;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Importing bank statements and matching their incoming transactions to payment requests. Matching goes in order: a
 * reference with a correct check character and an amount that covers the request is matched at once; the rest is
 * suggested by amount and name, or matched by hand. Nothing here is required: payments can always be recorded by hand.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class BankService {

    static final double NAME_THRESHOLD = 0.5;
    static final int MAX_SUGGESTIONS = 10;
    static final Duration UNMATCHED_KEPT = Duration.ofDays(90);
    static final Duration DISMISSED_KEPT = Duration.ofDays(365);

    private final BankFormatRepository formats;
    private final BankTransactionRepository transactions;
    private final PaymentRequestRepository requests;
    private final PaymentService payments;
    private final SpexregisterClient spexregister;
    private final Clock clock;

    public BankService(final BankFormatRepository formats, final BankTransactionRepository transactions, final PaymentRequestRepository requests,
                       final PaymentService payments, final SpexregisterClient spexregister, final Clock clock) {
        this.formats = formats;
        this.transactions = transactions;
        this.requests = requests;
        this.payments = payments;
        this.spexregister = spexregister;
        this.clock = clock;
    }

    /**
     * @param read       incoming and outgoing rows in the file
     * @param added      new incoming transactions kept
     * @param duplicates incoming transactions that were imported before
     * @param outgoing   rows that were not incoming and are not kept
     * @param matched    transactions matched at once by their reference
     * @param errors     rows that could not be read; when there are any nothing was imported
     */
    public record ImportResult(int read, int added, int duplicates, int outgoing, int matched, List<String> errors) {
    }

    @Transactional
    public ImportResult importStatement(final String actor, final String formatId, final String fileName, final String content) {
        final BankFormat format = formats.findById(formatId).orElseThrow(() -> new IllegalArgumentException("Unknown bank format"));
        final StatementParser.Result parsed = StatementParser.parse(content, format);

        if (!parsed.errors().isEmpty()) {
            return new ImportResult(0, 0, 0, 0, 0, parsed.errors());
        }

        final Map<String, Integer> seen = new HashMap<>();
        int added = 0;
        int duplicates = 0;
        int outgoing = 0;
        int matched = 0;

        for (final StatementParser.Row row : parsed.rows()) {
            if (row.ore() <= 0) {
                outgoing++;
                continue;
            }

            final String key = row.date() + "|" + row.ore() + "|" + row.text() + "|" + row.payer();
            final String fingerprint = sha256(key + "#" + seen.merge(key, 1, Integer::sum));

            if (transactions.existsByFingerprint(fingerprint)) {
                duplicates++;
                continue;
            }

            final BankTransaction transaction = transactions.save(new BankTransaction(row.date(), row.ore(), blankToNull(row.text()),
                    blankToNull(row.payer()), fingerprint, fileName, actor, clock.instant()));

            added++;
            if (matchByReference(actor, transaction)) {
                matched++;
            }
        }
        return new ImportResult(parsed.rows().size(), added, duplicates, outgoing, matched, List.of());
    }

    /**
     * A reference with a correct check character, on a request that can be paid and that the amount covers.
     */
    private boolean matchByReference(final String actor, final BankTransaction transaction) {
        for (final String reference : ReferenceFinder.valid(transaction.getText())) {
            final PaymentRequest request = requests.findByReference(reference).orElse(null);

            if (request != null && request.isPayable() && transaction.kronor() >= remaining(request)) {
                payments.recordPayment(actor, request.getId(), transaction.getBookedOn(), transaction.kronor(), howOf(transaction),
                        "Bank: " + (transaction.getFileName() == null ? "" : transaction.getFileName()), transaction.getPk());
                transaction.matched();
                return true;
            }
        }
        return false;
    }

    /**
     * What a transaction could be: requests with the same amount whose member's name, or the name given in
     * <em>I have paid</em>, resembles the payer's, reported ones first; and requests whose reference differs by one
     * character from a reference with a wrong check character in the text.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(final String transactionId) {
        final BankTransaction transaction = find(transactionId);
        final List<PaymentRequest> payable = requests.findByStatusInOrderByCreatedAtAsc(List.of(PaymentStatus.OPEN, PaymentStatus.REPORTED));
        final Map<String, Spexare> people = spexregister.byIds(payable.stream().map(PaymentRequest::getSpexareId).distinct().toList()).stream()
                .collect(Collectors.toMap(Spexare::id, Function.identity()));
        final List<Suggestion> found = new ArrayList<>();
        final String reference = transaction.getText() == null ? null : ReferenceFinder.invalid(transaction.getText()).stream().findFirst().orElse(null);
        final String who = (transaction.getPayer() == null ? "" : transaction.getPayer() + " ") + (transaction.getText() == null ? "" : transaction.getText());

        for (final PaymentRequest request : payable) {
            final Spexare person = people.get(request.getSpexareId());
            final double byName = Math.max(person == null ? 0 : NameMatcher.similarity(who, person.name()),
                    request.getPaidBy() == null ? 0 : NameMatcher.similarity(who, request.getPaidBy()));
            final boolean sameAmount = remaining(request) == transaction.kronor();
            final boolean nearReference = reference != null && !ReferenceFinder.nearest(reference, List.of(request.getReference())).isEmpty();

            if (nearReference) {
                found.add(new Suggestion(request.getId(), request.getReference(), person == null ? null : person.name(), request.getAmount(),
                        request.getStatus(), "REFERENCE", 2));
            } else if (sameAmount && byName >= NAME_THRESHOLD) {
                found.add(new Suggestion(request.getId(), request.getReference(), person == null ? null : person.name(), request.getAmount(),
                        request.getStatus(), "NAME", byName + (request.getStatus() == PaymentStatus.REPORTED ? 0.5 : 0)));
            }
        }
        return found.stream().sorted(Comparator.comparingDouble(Suggestion::score).reversed()).limit(MAX_SUGGESTIONS).toList();
    }

    /**
     * @param why   {@code REFERENCE} (a mistyped reference) or {@code NAME}
     * @param score higher is likelier
     */
    public record Suggestion(String requestId, String reference, String spexareName, int amount, PaymentStatus status, String why, double score) {
    }

    /**
     * Matches a transaction by hand to one or several requests (two members paid together). One request gets the whole
     * transaction, more than asked being a gift; several get what they still lack, in order, the last one what is left.
     * A request that is paid already by hand is linked to the transaction instead of being paid twice.
     */
    @Transactional
    public BankTransaction match(final String actor, final String transactionId, final List<String> requestIds) {
        final BankTransaction transaction = find(transactionId);

        if (transaction.getStatus() != TransactionStatus.UNMATCHED) {
            throw new IllegalStateException("The transaction is " + transaction.getStatus());
        }
        if (requestIds == null || requestIds.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one request");
        }

        int left = transaction.kronor();

        for (int i = 0; i < requestIds.size(); i++) {
            final PaymentRequest request = requests.findById(requestIds.get(i)).orElseThrow(() -> new IllegalArgumentException("Unknown request"));

            if (request.getStatus() == PaymentStatus.MATCHED) {
                if (requestIds.size() > 1) {
                    throw new IllegalArgumentException("A request that is paid already can only be linked on its own");
                }
                payments.linkTransaction(request.getId(), transaction.getPk());
                left = 0;
                continue;
            }

            final boolean last = i == requestIds.size() - 1;
            final int amount = last ? left : Math.min(left, remaining(request));

            if (amount <= 0) {
                throw new IllegalArgumentException("The transaction is not enough for all the requests");
            }
            payments.recordPayment(actor, request.getId(), transaction.getBookedOn(), amount, howOf(transaction),
                    "Bank: " + (transaction.getFileName() == null ? "" : transaction.getFileName()), transaction.getPk());
            left -= amount;
        }
        transaction.matched();
        return transaction;
    }

    /**
     * Not a membership payment: its content is deleted at once.
     */
    @Transactional
    public BankTransaction dismiss(final String transactionId) {
        final BankTransaction transaction = find(transactionId);

        if (transaction.getStatus() != TransactionStatus.UNMATCHED) {
            throw new IllegalStateException("The transaction is " + transaction.getStatus());
        }
        transaction.dismiss(clock.instant());
        return transaction;
    }

    @Transactional(readOnly = true)
    public List<BankTransaction> unmatched() {
        return transactions.findByStatusOrderByBookedOnAsc(TransactionStatus.UNMATCHED);
    }

    /**
     * Removes what is kept longer than it should be: unmatched transactions after 90 days, and dismissed ones' fingerprints
     * after a year. The treasurer is not left with bank texts nobody needs.
     *
     * @return how many were removed
     */
    @Transactional
    public int removeOld() {
        final List<BankTransaction> unmatched = transactions.findByStatusAndImportedAtBefore(TransactionStatus.UNMATCHED, clock.instant().minus(UNMATCHED_KEPT));
        final List<BankTransaction> dismissed = transactions.findByStatusAndDismissedAtBefore(TransactionStatus.DISMISSED, clock.instant().minus(DISMISSED_KEPT));

        transactions.deleteAll(unmatched);
        transactions.deleteAll(dismissed);
        return unmatched.size() + dismissed.size();
    }

    private BankTransaction find(final String id) {
        return transactions.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown transaction " + id));
    }

    private int remaining(final PaymentRequest request) {
        return payments.remaining(request);
    }

    private static PaymentHow howOf(final BankTransaction transaction) {
        return transaction.getText() != null && transaction.getText().toUpperCase(Locale.ROOT).contains("SWISH") ? PaymentHow.SWISH : PaymentHow.BANK_TRANSFER;
    }

    private static String blankToNull(final String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String sha256(final String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
