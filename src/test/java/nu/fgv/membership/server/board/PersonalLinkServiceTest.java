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

package nu.fgv.membership.server.board;

import nu.fgv.membership.server.payment.PaymentProperties;
import nu.fgv.membership.server.payment.PaymentSettings;
import nu.fgv.membership.server.payment.PaymentRequest;
import nu.fgv.membership.server.payment.PaymentRequestRepository;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.SwishLinks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalLinkServiceTest {

    private final PersonalLinkRepository links = mock(PersonalLinkRepository.class);
    private final PaymentRequestRepository requests = mock(PaymentRequestRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OptOutRepository optOuts = mock(OptOutRepository.class);
    private final List<PersonalLink> saved = new ArrayList<>();
    private final PaymentSettings properties = PaymentSettings.fixed(new PaymentProperties("1234567890", "1234-5", "FGV", "http://localhost:1/qr", Duration.ofDays(3), "FGV"), List.of());
    private Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private PaymentRequest request;
    private PersonalLinkService service;

    @BeforeEach
    void setUp() {
        request = new PaymentRequest("FGV27ABCD", "sp-1", 200, "user", Set.of(), clock.instant());
        ReflectionTestUtils.setField(request, "pk", 7L);
        service = new PersonalLinkService(links, requests, payments, properties, new SwishLinks(RestClient.create(), properties), optOuts, clock);
        when(links.save(any(PersonalLink.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(links.findByTokenHash(anyString())).thenAnswer(call -> saved.stream().filter(link -> PersonalLinkService.hash("x").length() > 0
                && java.util.Objects.equals(ReflectionTestUtils.getField(link, "tokenHash"), call.getArgument(0))).findFirst());
        when(requests.findById(7L)).thenReturn(Optional.of(request));
        when(payments.remaining(request)).thenReturn(200);
        when(payments.yearsOf(request)).thenReturn(List.of(2027));
    }

    @Test
    void theTokenIsLongAndOnlyItsHashIsKept() {
        final String token = service.create(request);

        assertThat(token).hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]+");
        assertThat(ReflectionTestUtils.getField(saved.getFirst(), "tokenHash")).isEqualTo(PersonalLinkService.hash(token)).isNotEqualTo(token);
        assertThat(service.create(request)).isNotEqualTo(token);
    }

    @Test
    void theTokenOpensThePaymentRequestAndNothingElse() {
        final var page = service.page(service.create(request)).orElseThrow();

        assertThat(page.reference()).isEqualTo("FGV27ABCD");
        assertThat(page.amount()).isEqualTo(200);
        assertThat(page.years()).containsExactly(2027);
        assertThat(page.swishLink()).startsWith("swish://payment");
    }

    @Test
    void aWrongOrMissingTokenOpensNothing() {
        service.create(request);

        assertThat(service.page("short")).isEmpty();
        assertThat(service.page(null)).isEmpty();
        assertThat(service.page("x".repeat(43))).isEmpty();
        assertThat(service.report("x".repeat(43), null)).isFalse();
        assertThat(service.optOut("x".repeat(43))).isFalse();
    }

    @Test
    void theLinkStopsWorkingAtTheEndOfTheYear() {
        final String token = service.create(request);

        clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new PersonalLinkService(links, requests, payments, properties, new SwishLinks(RestClient.create(), properties), optOuts, clock);

        assertThat(service.page(token)).isEmpty();
    }

    @Test
    void sayingYouHavePaidWorksWithoutLoggingIn() {
        final String token = service.create(request);

        assertThat(service.report(token, "Partner")).isTrue();
        verify(payments).reportBy(request.getId(), "Partner");
    }

    @Test
    void aPaidRequestIsNotReportedAgainAndShowsNoPaymentDetails() {
        request.matched();
        final String token = service.create(request);

        assertThat(service.report(token, null)).isTrue();
        verify(payments, never()).reportBy(anyString(), any());
        assertThat(service.page(token).orElseThrow().swishLink()).isNull();
    }

    @Test
    void dontRemindMeAgainIsRememberedOnce() {
        final String token = service.create(request);

        assertThat(service.optOut(token)).isTrue();
        verify(optOuts).save(any(OptOut.class));

        when(optOuts.existsById("sp-1")).thenReturn(true);
        assertThat(service.optOut(token)).isTrue();
        verify(optOuts, org.mockito.Mockito.times(1)).save(any(OptOut.class));
    }
}
