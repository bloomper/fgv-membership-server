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
import nu.fgv.membership.server.payment.PaymentReference;
import nu.fgv.membership.server.payment.PaymentRequest;
import nu.fgv.membership.server.payment.PaymentRequestRepository;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentStatus;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private final BankFormatRepository formats = mock(BankFormatRepository.class);
    private final BankTransactionRepository transactions = mock(BankTransactionRepository.class);
    private final PaymentRequestRepository requests = mock(PaymentRequestRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final SpexregisterClient spexregister = mock(SpexregisterClient.class);
    private final List<BankTransaction> stored = new ArrayList<>();
    private BankFormat format;
    private BankService service;
    private PaymentRequest greta;
    private PaymentRequest ingrid;

    @BeforeEach
    void setUp() {
        format = new BankFormat(clock.instant());
        format.update("Test", "UTF-8", ";", 1, 1, 2, 3, 4, "yyyy-MM-dd", true);
        service = new BankService(formats, transactions, requests, payments, spexregister, clock);
        greta = request("sp-greta", 200);
        ingrid = request("sp-ingrid", 200);

        when(formats.findById(anyString())).thenReturn(Optional.of(format));
        when(transactions.existsByFingerprint(anyString())).thenAnswer(call -> fingerprints.contains(call.<String>getArgument(0)));
        when(transactions.save(any(BankTransaction.class))).thenAnswer(call -> {
            final BankTransaction transaction = call.getArgument(0);

            stored.add(transaction);
            fingerprints.add(transaction.getFingerprint());
            return transaction;
        });
        when(requests.findByReference(greta.getReference())).thenReturn(Optional.of(greta));
        when(requests.findByReference(ingrid.getReference())).thenReturn(Optional.of(ingrid));
        when(requests.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(greta, ingrid));
        when(requests.findById(greta.getId())).thenReturn(Optional.of(greta));
        when(requests.findById(ingrid.getId())).thenReturn(Optional.of(ingrid));
        when(payments.remaining(any())).thenAnswer(call -> call.<PaymentRequest>getArgument(0).getAmount());
        when(spexregister.byIds(any())).thenReturn(List.of(new Spexare("sp-greta", "Greta", "Garbo", null), new Spexare("sp-ingrid", "Ingrid", "Bergman", null)));
    }

    private final java.util.Set<String> fingerprints = new java.util.HashSet<>();

    private PaymentRequest request(final String spexare, final int amount) {
        return new PaymentRequest(PaymentReference.generate(Year.of(2026)), spexare, amount, "user", Set.of(), clock.instant());
    }

    private String statement(final String... rows) {
        return "Datum;Belopp;Text;Namn\n" + String.join("\n", rows);
    }

    @Test
    void aTransactionWithAValidReferenceThatCoversTheRequestIsMatchedAtOnce() {
        final var result = service.importStatement("treasurer", "f", "nov.csv", statement("2026-11-20;200,00;Swish " + greta.getReference() + ";GRETA GARBO"));

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.matched()).isEqualTo(1);
        verify(payments).recordPayment(eq("treasurer"), eq(greta.getId()), eq(LocalDate.of(2026, 11, 20)), eq(200), eq(PaymentHow.SWISH), anyString(), any());
        assertThat(stored.getFirst().getStatus()).isEqualTo(TransactionStatus.MATCHED);
    }

    @Test
    void anAmountThatIsTooSmallIsNotMatchedAtOnce() {
        final var result = service.importStatement("treasurer", "f", "nov.csv", statement("2026-11-20;150,00;" + greta.getReference() + ";Greta"));

        assertThat(result.matched()).isZero();
        assertThat(stored.getFirst().getStatus()).isEqualTo(TransactionStatus.UNMATCHED);
        verify(payments, never()).recordPayment(anyString(), anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void aMoreGenerousAmountIsMatchedAndKeptAsAGift() {
        service.importStatement("treasurer", "f", "nov.csv", statement("2026-11-20;300,00;" + greta.getReference() + ";Greta"));

        verify(payments).recordPayment(anyString(), eq(greta.getId()), any(), eq(300), eq(PaymentHow.BANK_TRANSFER), anyString(), any());
    }

    @Test
    void onlyIncomingTransactionsAreKept() {
        final var result = service.importStatement("treasurer", "f", "nov.csv", statement("2026-11-20;-200,00;Hyra;", "2026-11-21;0,00;Ränta;"));

        assertThat(result.outgoing()).isEqualTo(2);
        assertThat(result.added()).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    void theSameFileTwiceAddsNothingButIdenticalRowsInOneFileAreBothKept() {
        final String file = statement("2026-11-20;100,00;Gåva;Anna", "2026-11-20;100,00;Gåva;Anna");

        assertThat(service.importStatement("t", "f", "a.csv", file).added()).isEqualTo(2);
        assertThat(service.importStatement("t", "f", "a.csv", file).duplicates()).isEqualTo(2);
        assertThat(stored).hasSize(2);
    }

    @Test
    void aFileWithAnUnreadableRowImportsNothing() {
        final var result = service.importStatement("t", "f", "a.csv", statement("2026-11-20;100,00;Gåva;Anna", "oops;1;2;3"));

        assertThat(result.errors()).hasSize(1);
        assertThat(stored).isEmpty();
    }

    @Test
    void aSameAmountAndANamedPayerSuggestsTheRightRequestAndNotTheWrongOne() {
        service.importStatement("t", "f", "a.csv", statement("2026-11-20;200,00;Swish betalning;Greta Garbo"));

        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        final var suggestions = service.suggestions(stored.getFirst().getId());

        assertThat(suggestions).extracting(BankService.Suggestion::requestId).containsExactly(greta.getId());
        assertThat(suggestions.getFirst().why()).isEqualTo("NAME");
    }

    @Test
    void aReportedRequestComesBeforeAnOpenOne() {
        greta.report(null, clock.instant());
        ingrid = request("sp-greta", 200);
        when(requests.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(ingrid, greta));
        service.importStatement("t", "f", "a.csv", statement("2026-11-20;200,00;Swish;Greta Garbo"));
        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        assertThat(service.suggestions(stored.getFirst().getId())).extracting(BankService.Suggestion::requestId).containsExactly(greta.getId(), ingrid.getId());
    }

    @Test
    void aMistypedReferenceSuggestsTheRequestItAlmostIs() {
        final char wrong = greta.getReference().charAt(8) == 'A' ? 'B' : 'A';

        service.importStatement("t", "f", "a.csv", statement("2026-11-20;50,00;" + greta.getReference().substring(0, 8) + wrong + ";Någon"));
        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        final var suggestions = service.suggestions(stored.getFirst().getId());

        assertThat(suggestions.getFirst().requestId()).isEqualTo(greta.getId());
        assertThat(suggestions.getFirst().why()).isEqualTo("REFERENCE");
    }

    @Test
    void oneTransactionCanPayForSeveralRequestsAndTheLastGetsWhatIsLeft() {
        service.importStatement("t", "f", "a.csv", statement("2026-11-20;450,00;Två medlemmar;Greta Garbo"));
        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        service.match("treasurer", stored.getFirst().getId(), List.of(greta.getId(), ingrid.getId()));

        verify(payments).recordPayment(eq("treasurer"), eq(greta.getId()), any(), eq(200), any(), anyString(), any());
        verify(payments).recordPayment(eq("treasurer"), eq(ingrid.getId()), any(), eq(250), any(), anyString(), any());
        assertThat(stored.getFirst().getStatus()).isEqualTo(TransactionStatus.MATCHED);
    }

    @Test
    void aTransactionThatIsNotEnoughForAllTheRequestsIsRefused() {
        service.importStatement("t", "f", "a.csv", statement("2026-11-20;200,00;Två medlemmar;Greta Garbo"));
        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        assertThatThrownBy(() -> service.match("treasurer", stored.getFirst().getId(), List.of(greta.getId(), ingrid.getId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTransactionForARequestPaidByHandIsLinkedNotPaidTwice() {
        greta.matched();
        service.importStatement("t", "f", "a.csv", statement("2026-11-20;200,00;Swish;Greta Garbo"));
        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        service.match("treasurer", stored.getFirst().getId(), List.of(greta.getId()));

        verify(payments).linkTransaction(greta.getId(), stored.getFirst().getPk());
        verify(payments, never()).recordPayment(anyString(), anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void aDismissedTransactionLosesItsContent() {
        service.importStatement("t", "f", "a.csv", statement("2026-11-20;99,00;Privat;Anna Andersson"));
        when(transactions.findById(stored.getFirst().getId())).thenReturn(Optional.of(stored.getFirst()));

        final var dismissed = service.dismiss(stored.getFirst().getId());

        assertThat(dismissed.getStatus()).isEqualTo(TransactionStatus.DISMISSED);
        assertThat(dismissed.getText()).isNull();
        assertThat(dismissed.getPayer()).isNull();
        assertThatThrownBy(() -> service.dismiss(stored.getFirst().getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRequestThatIsNotPayableIsNotMatchedByReference() {
        greta.cancel();

        assertThat(greta.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(service.importStatement("t", "f", "a.csv", statement("2026-11-20;200,00;" + greta.getReference() + ";Greta")).matched()).isZero();
    }
}
