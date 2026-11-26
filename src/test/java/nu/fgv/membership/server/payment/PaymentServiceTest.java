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
import nu.fgv.membership.server.membership.MembershipHow;
import nu.fgv.membership.server.membership.MembershipRepository;
import nu.fgv.membership.server.membership.MembershipService;
import nu.fgv.membership.server.membership.MembershipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final String USER = "user-1";
    private static final String SPEXARE = "0197d6a0-0000-7000-8000-000000000001";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private final Map<String, PaymentRequest> stored = new HashMap<>();
    private final Map<String, Membership> memberships = new HashMap<>();
    private final List<Payment> paid = new ArrayList<>();
    private final Map<String, FeeLevel> feeLevels = new HashMap<>();
    private final PaymentRequestRepository requests = mock(PaymentRequestRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final FeeLevelRepository fees = mock(FeeLevelRepository.class);
    private final MembershipService membershipService = mock(MembershipService.class);
    private final MembershipRepository membershipRepository = mock(MembershipRepository.class);
    private final SpexregisterClient spexregister = mock(SpexregisterClient.class);
    private final PaymentMails mails = mock(PaymentMails.class);
    private final PaymentSettings properties = PaymentSettings.fixed(new PaymentProperties("123 456 78 90", "1234-5, 678 901 234-5", "FGV",
            "http://qr.test", Duration.ofDays(3), "FGV"), List.of());
    private final Spexare spexare = new Spexare(SPEXARE, "Greta", "Garbo", "greta@example.org");

    private PaymentService service;
    private FeeLevel ordinary2026;
    private FeeLevel ordinary2027;
    private FeeLevel life;

    @BeforeEach
    void setUp() {
        service = new PaymentService(requests, payments, fees, membershipService, membershipRepository, spexregister, mails, properties, clock);
        ordinary2026 = fee(2026, false, "Ordinarie", 200);
        ordinary2027 = fee(2027, false, "Ordinarie", 200);
        life = fee(null, true, "Livstid", 2000);

        when(payments.save(any(Payment.class))).thenAnswer(call -> {
            paid.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(payments.findByRequestPk(any())).thenAnswer(call -> List.copyOf(paid));
        when(spexregister.forUser(USER)).thenReturn(Optional.of(spexare));
        when(spexregister.forUser("stranger")).thenReturn(Optional.empty());
        when(spexregister.byIds(any())).thenReturn(List.of(spexare));
        when(requests.save(any(PaymentRequest.class))).thenAnswer(call -> {
            final PaymentRequest request = call.getArgument(0);

            stored.put(request.getId(), request);
            return request;
        });
        when(requests.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(membershipRepository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(memberships.get(call.<String>getArgument(0))));
        when(membershipRepository.findBySpexareId(SPEXARE)).thenAnswer(call -> new ArrayList<>(memberships.values()));
        when(membershipService.record(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any()))
                .thenAnswer(call -> {
                    final Membership membership = new Membership(call.getArgument(0), call.getArgument(1), call.getArgument(2), clock.instant());

                    membership.update(call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6), clock.instant());
                    memberships.put(membership.getId(), membership);
                    return membership;
                });
        when(membershipService.setStatus(anyString(), any(), any(), any())).thenAnswer(call -> {
            final Membership membership = memberships.get(call.<String>getArgument(0));

            membership.update(membership.isLifetime(), call.getArgument(1), call.getArgument(2), call.getArgument(3), clock.instant());
            return membership;
        });
    }

    private FeeLevel fee(final Integer year, final boolean lifetime, final String name, final int amount) {
        final FeeLevel level = new FeeLevel("FGV", year, lifetime, name, name, amount, clock.instant());

        feeLevels.put(level.getId(), level);
        when(fees.findById(level.getId())).thenReturn(Optional.of(level));
        return level;
    }

    @Test
    void aRequestForTwoYearsAddsTheFeesAndMakesPendingMemberships() {
        final PaymentView view = service.request(USER, List.of(ordinary2026.getId(), ordinary2027.getId()));

        assertThat(view.amount()).isEqualTo(400);
        assertThat(view.status()).isEqualTo(PaymentStatus.OPEN);
        assertThat(PaymentReference.isValid(view.reference())).isTrue();
        assertThat(view.reference()).startsWith("FGV26");
        assertThat(view.memberships()).extracting(PaymentView.Item::year).containsExactly(2026, 2027);
        assertThat(view.memberships()).extracting(PaymentView.Item::status).containsOnly("PENDING");
        assertThat(view.payee().swishNumber()).isEqualTo("123 456 78 90");
    }

    @Test
    void aLifeMembershipStartsThisYear() {
        final PaymentView view = service.request(USER, List.of(life.getId()));

        assertThat(view.memberships()).singleElement().satisfies(item -> {
            assertThat(item.lifetime()).isTrue();
            assertThat(item.year()).isEqualTo(2026);
        });
    }

    @Test
    void oneFeeForEachYearAndSomethingToPay() {
        assertThatThrownBy(() -> service.request(USER, List.of(ordinary2026.getId(), fee(2026, false, "Student", 100).getId())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.request(USER, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void youCannotPayForAYearYouAreAlreadyAMemberIn() {
        final Membership paid = new Membership(SPEXARE, "FGV", 2026, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.SWISH, null, clock.instant());
        memberships.put(paid.getId(), paid);

        assertThatThrownBy(() -> service.request(USER, List.of(ordinary2026.getId()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLifeMemberCannotPayAgain() {
        final Membership lifelong = new Membership(SPEXARE, "FGV", 2019, clock.instant());

        lifelong.update(true, MembershipStatus.PAID, MembershipHow.CASH, null, clock.instant());
        memberships.put(lifelong.getId(), lifelong);

        assertThatThrownBy(() -> service.request(USER, List.of(ordinary2026.getId()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetiredFeeIsNotOffered() {
        ordinary2026.setRetired(true);

        assertThatThrownBy(() -> service.request(USER, List.of(ordinary2026.getId()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUserWithoutASpexareCannotPay() {
        assertThatThrownBy(() -> service.request("stranger", List.of(ordinary2026.getId()))).isInstanceOf(PaymentService.NoSpexareException.class);
    }

    @Test
    void reportingMarksTheRequestAndItsMembershipsAndRemembersWhoPaid() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));
        final PaymentView reported = service.report(USER, made.id(), " Partner Garbo ");

        assertThat(reported.status()).isEqualTo(PaymentStatus.REPORTED);
        assertThat(reported.paidBy()).isEqualTo("Partner Garbo");
        assertThat(reported.memberships()).extracting(PaymentView.Item::status).containsOnly("REPORTED");
    }

    @Test
    void onlyThePayerCanReport() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        when(spexregister.forUser("other")).thenReturn(Optional.of(new Spexare("someone-else", "A", "B", null)));

        assertThatThrownBy(() -> service.report("other", made.id(), null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void recordingAPaymentMakesTheMembershipsCountAndSendsAReceipt() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));
        final PaymentView paid = service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 200, PaymentHow.SWISH, "Swish");

        assertThat(paid.status()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(paid.memberships()).extracting(PaymentView.Item::status).containsOnly("PAID");
        verify(payments).save(any(Payment.class));
        verify(mails).receipt(eq(spexare), any(PaymentView.class));
    }

    @Test
    void anAmountThatIsTooSmallLeavesTheRequestOpenWithTheDifferenceShown() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));
        final PaymentView partly = service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 150, PaymentHow.SWISH, null);

        assertThat(partly.status()).isEqualTo(PaymentStatus.OPEN);
        assertThat(partly.paid()).isEqualTo(150);
        assertThat(partly.memberships()).extracting(PaymentView.Item::status).containsOnly("PENDING");
        verify(mails, never()).receipt(any(), any());

        final PaymentView rest = service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 28), 50, PaymentHow.SWISH, null);

        assertThat(rest.status()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(rest.paid()).isEqualTo(200);
        verify(mails).receipt(any(), any());
    }

    @Test
    void moreThanAskedForIsKeptAsAGift() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));
        final PaymentView paidMore = service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 300, PaymentHow.CASH, null);

        assertThat(paidMore.status()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(paidMore.paid()).isEqualTo(300);
    }

    @Test
    void nothingCanBeRecordedForNothing() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        assertThatThrownBy(() -> service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 0, PaymentHow.CASH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPaymentCanBeUndoneInTheSameYearAndTheMembershipsStopCounting() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        service.report(USER, made.id(), null);
        service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 200, PaymentHow.SWISH, null);

        final Payment payment = paid.getFirst();

        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doAnswer(call -> paid.remove(call.<Payment>getArgument(0))).when(payments).delete(any(Payment.class));
        when(requests.findById(payment.getRequestPk())).thenReturn(Optional.of(stored.get(made.id())));

        final PaymentView undone = service.undoPayment(payment.getId());

        assertThat(undone.status()).isEqualTo(PaymentStatus.REPORTED);
        assertThat(undone.paid()).isZero();
        assertThat(undone.memberships()).extracting(PaymentView.Item::status).containsOnly("REPORTED");
    }

    @Test
    void aRequestIsPaidOnce() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 200, PaymentHow.CASH, null);

        assertThatThrownBy(() -> service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 28), 200, PaymentHow.CASH, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingCancelsTheMemberships() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));
        final PaymentView cancelled = service.cancel(made.id());

        assertThat(cancelled.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(cancelled.memberships()).extracting(PaymentView.Item::status).containsOnly("CANCELLED");
    }

    @Test
    void anOpenRequestIsRemindedOnceAfterThreeDays() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));
        final PaymentRequest request = stored.get(made.id());

        when(requests.findByStatusAndReminderSentAtIsNullAndCreatedAtBefore(eq(PaymentStatus.OPEN), any()))
                .thenAnswer(call -> request.getReminderSentAt() == null ? List.of(request) : List.of());

        assertThat(service.remind()).isEqualTo(1);
        assertThat(service.remind()).isZero();
        verify(mails).reminder(eq(spexare), any(PaymentView.class));
    }

    @Test
    void anotherPersonCannotReadYourRequestButTheTreasurerCan() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        when(spexregister.forUser("other")).thenReturn(Optional.of(new Spexare("someone-else", "A", "B", null)));

        assertThatThrownBy(() -> service.get("other", made.id(), false)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.get("other", made.id(), true).spexareName()).isEqualTo("Greta Garbo");
    }

    @Test
    void theAdministratorsAreToldWhenSomeoneSaysTheyHavePaid() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        service.report(USER, made.id(), "Partner Garbo");

        verify(mails).adminNotice(eq(PaymentMails.Event.REPORTED), eq("Greta Garbo"), eq(200), eq(made.reference()), eq("2026"),
                eq("Betalat av Partner Garbo"));
    }

    @Test
    void theAdministratorsAreToldWhenTheShopReportsAPayment() {
        final var view = service.recordExternal("shop", SPEXARE, 2026, false, 200, LocalDate.of(2026, 11, 25), "Online: order-1");

        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(view.get().memberships()).extracting(PaymentView.Item::status).containsOnly("PAID");
        verify(mails).receipt(eq(spexare), any(PaymentView.class));
        verify(mails).adminNotice(eq(PaymentMails.Event.ONLINE), eq("Greta Garbo"), eq(200), eq(view.get().reference()), eq("2026"), eq("Online: order-1"));
    }

    @Test
    void aShopPaymentFromSomeoneAlreadyAMemberIsReportedToTheAdministratorsAndNotRecorded() {
        final Membership paid = new Membership(SPEXARE, "FGV", 2026, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.SWISH, null, clock.instant());
        memberships.put(paid.getId(), paid);

        assertThat(service.recordExternal("shop", SPEXARE, 2026, false, 200, LocalDate.of(2026, 11, 25), "Online: order-2")).isEmpty();

        verify(mails).adminNotice(eq(PaymentMails.Event.ALREADY_MEMBER), eq("Greta Garbo"), eq(200), eq("Online: order-2"), eq("2026"), eq("Online: order-2"));
        verify(payments, never()).save(any());
    }

    @Test
    void aRevivalPaysForSeveralParticipantsWithOneRequest() {
        final var result = service.requestForRevival("revival-service", "Nyuppsättning 2027", List.of("sp-a", "sp-b", "sp-c"), ordinary2027.getId());

        assertThat(result.included()).isEqualTo(3);
        assertThat(result.request().amount()).isEqualTo(600);
        assertThat(result.request().spexareId()).isNull();
        assertThat(result.request().spexareName()).isEqualTo("Nyuppsättning 2027");
        assertThat(PaymentReference.isValid(result.request().reference())).isTrue();
        assertThat(memberships.values()).extracting(Membership::getSpexareId).containsExactlyInAnyOrder("sp-a", "sp-b", "sp-c");
    }

    @Test
    void participantsWhoAreMembersAlreadyAreLeftOutAndReported() {
        final Membership paid = new Membership("sp-b", "FGV", 2027, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.SWISH, null, clock.instant());
        memberships.put(paid.getId(), paid);
        when(membershipRepository.findBySpexareId("sp-b")).thenReturn(List.of(paid));

        final var result = service.requestForRevival("revival-service", "Nyuppsättning 2027", List.of("sp-a", "sp-b"), ordinary2027.getId());

        assertThat(result.included()).isEqualTo(1);
        assertThat(result.alreadyMembers()).containsExactly("sp-b");
        assertThat(result.request().amount()).isEqualTo(200);
    }

    @Test
    void aRevivalRequestWithNobodyToPayForIsRefused() {
        final Membership paid = new Membership("sp-b", "FGV", 2027, clock.instant());

        paid.update(false, MembershipStatus.WAIVED, MembershipHow.WAIVED, "honorary", clock.instant());
        when(membershipRepository.findBySpexareId("sp-b")).thenReturn(List.of(paid));

        assertThatThrownBy(() -> service.requestForRevival("revival-service", "Nyuppsättning", List.of("sp-b"), ordinary2027.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requestForRevival("revival-service", " ", List.of("sp-a"), ordinary2027.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requestForRevival("revival-service", "Nyuppsättning", List.of("sp-a"), life.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenTheTreasurerRecordsTheRevivalsPaymentTheMembershipsArePaidByTheRevival() {
        final var made = service.requestForRevival("revival-service", "Nyuppsättning 2027", List.of("sp-a", "sp-b"), ordinary2027.getId());
        final PaymentView paidView = service.recordPayment("treasurer", made.request().id(), LocalDate.of(2026, 12, 1), 400, PaymentHow.BANK_TRANSFER, null);

        assertThat(paidView.status()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(memberships.values()).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MembershipStatus.PAID);
            assertThat(m.getHow()).isEqualTo(MembershipHow.REVIVAL);
        });
        verify(mails, never()).receipt(any(), any());
    }

    @Test
    void nobodyCanReportOrReadARevivalsRequestAsTheirOwn() {
        final var made = service.requestForRevival("revival-service", "Nyuppsättning 2027", List.of("sp-a"), ordinary2027.getId());

        assertThatThrownBy(() -> service.report(USER, made.request().id(), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.get(USER, made.request().id(), false)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.get(USER, made.request().id(), true).spexareName()).isEqualTo("Nyuppsättning 2027");
    }

    @Test
    void revivalRequestsAreNotRemindedByEmail() {
        service.requestForRevival("revival-service", "Nyuppsättning 2027", List.of("sp-a"), ordinary2027.getId());
        when(requests.findByStatusAndReminderSentAtIsNullAndCreatedAtBefore(eq(PaymentStatus.OPEN), any())).thenReturn(List.copyOf(stored.values()));

        assertThat(service.remind()).isZero();
        verify(mails, never()).reminder(any(), any());
    }

    private FeeLevel familyFee(final int year, final int amount) {
        final FeeLevel level = new FeeLevel("FGV", year, FeeKind.FAMILY, "Familj", "Family", amount, clock.instant());

        feeLevels.put(level.getId(), level);
        when(fees.findById(level.getId())).thenReturn(Optional.of(level));
        return level;
    }

    @Test
    void oneFamilyFeePaysForTheWholeFamily() {
        final FeeLevel family = familyFee(2026, 350);
        final PaymentView view = service.request(SPEXARE, USER, List.of(family.getId()), "Maken Gösta och barnen Lisa och Pelle", List.of("sp-b", "sp-c"));

        assertThat(view.amount()).isEqualTo(350);
        assertThat(view.family()).isTrue();
        assertThat(view.familyIncomplete()).isFalse();
        assertThat(view.familyNote()).isEqualTo("Maken Gösta och barnen Lisa och Pelle");
        assertThat(view.memberships()).extracting(PaymentView.Item::spexareId).containsExactlyInAnyOrder(SPEXARE, "sp-b", "sp-c");
        assertThat(view.memberships()).extracting(PaymentView.Item::status).containsOnly("PENDING");
        assertThat(memberships.values()).extracting(Membership::getFamilyId).doesNotContainNull().containsOnly(memberships.values().iterator().next().getFamilyId());
    }

    @Test
    void thePayerAloneIsAFamilyRequestThatTheBoardCompletes() {
        final FeeLevel family = familyFee(2026, 350);
        final PaymentView view = service.request(USER, List.of(family.getId()));

        assertThat(view.family()).isTrue();
        assertThat(view.familyIncomplete()).isTrue();
    }

    @Test
    void payingMakesEveryFamilyMemberAMember() {
        final FeeLevel family = familyFee(2026, 350);
        final PaymentView made = service.request(SPEXARE, USER, List.of(family.getId()), null, List.of("sp-b", "sp-c"));
        final PaymentView paidView = service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 350, PaymentHow.SWISH, null);

        assertThat(paidView.status()).isEqualTo(PaymentStatus.MATCHED);
        assertThat(paidView.memberships()).hasSize(3).extracting(PaymentView.Item::status).containsOnly("PAID");
    }

    @Test
    void theBoardCanAttachFamilyMembersLaterAndTheyGetTheStateOfThePayer() {
        final FeeLevel family = familyFee(2026, 350);
        final PaymentView made = service.request(USER, List.of(family.getId()));

        service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 350, PaymentHow.SWISH, "Swish");

        final PaymentView completed = service.addFamilyMembers(made.id(), List.of("sp-b", SPEXARE, "sp-b"));

        assertThat(completed.memberships()).hasSize(2);
        assertThat(completed.familyIncomplete()).isFalse();
        assertThat(completed.memberships()).extracting(PaymentView.Item::status).containsOnly("PAID");
    }

    @Test
    void anotherRequestCannotBeCompletedAsAFamily() {
        final PaymentView made = service.request(USER, List.of(ordinary2026.getId()));

        assertThatThrownBy(() -> service.addFamilyMembers(made.id(), List.of("sp-b"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFamilyMemberWhoIsAMemberAlreadyIsRefused() {
        final FeeLevel family = familyFee(2026, 350);
        final Membership paid = new Membership("sp-b", "FGV", 2026, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.SWISH, null, clock.instant());
        when(membershipRepository.findBySpexareId("sp-b")).thenReturn(List.of(paid));

        assertThatThrownBy(() -> service.request(SPEXARE, USER, List.of(family.getId()), null, List.of("sp-b"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFamilyCannotBeLargerThanTen() {
        final FeeLevel family = familyFee(2026, 350);
        final List<String> many = java.util.stream.IntStream.range(0, 10).mapToObj(i -> "sp-" + i).toList();

        assertThatThrownBy(() -> service.request(SPEXARE, USER, List.of(family.getId()), null, many)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.request(SPEXARE, USER, List.of(family.getId()), null, many.subList(0, 9)).memberships()).hasSize(10);
    }

    @Test
    void standardAndFamilyFeeCannotBothBePaidForTheSameYear() {
        final FeeLevel family = familyFee(2026, 350);

        assertThatThrownBy(() -> service.request(USER, List.of(ordinary2026.getId(), family.getId()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void undoingAFamilyPaymentTakesTheMembershipFromEveryone() {
        final FeeLevel family = familyFee(2026, 350);
        final PaymentView made = service.request(SPEXARE, USER, List.of(family.getId()), null, List.of("sp-b"));

        service.recordPayment("treasurer", made.id(), LocalDate.of(2026, 11, 27), 350, PaymentHow.SWISH, null);

        final Payment payment = paid.getFirst();

        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doAnswer(call -> paid.remove(call.<Payment>getArgument(0))).when(payments).delete(any(Payment.class));
        when(requests.findById(payment.getRequestPk())).thenReturn(Optional.of(stored.get(made.id())));

        assertThat(service.undoPayment(payment.getId()).memberships()).extracting(PaymentView.Item::status).containsOnly("PENDING");
    }

    @Test
    void aLifeFeeCanStartInALaterYearAndIsNotOfferedBefore() {
        final FeeService feeService = new FeeService(fees, clock);
        final FeeLevel later = new FeeLevel("FGV", 2028, FeeKind.LIFETIME, "Livstid 2028", "Life 2028", 3000, clock.instant());

        when(fees.findByRetiredFalseOrderByAmountDesc()).thenReturn(List.of(later, life, ordinary2026));

        assertThat(feeService.available(null)).containsExactly(life, ordinary2026);
        assertThat(feeService.available(2026)).containsExactly(life, ordinary2026);
    }

    @Test
    void aSupportFeeMakesASupportMembershipForOnePerson() {
        final FeeLevel support = new FeeLevel("FGV", 2026, FeeKind.SUPPORT, "Stöd", "Support", 100, clock.instant());

        feeLevels.put(support.getId(), support);
        when(fees.findById(support.getId())).thenReturn(Optional.of(support));

        final PaymentView view = service.request(USER, List.of(support.getId()));

        assertThat(view.amount()).isEqualTo(100);
        assertThat(view.family()).isFalse();
        assertThat(memberships.values()).singleElement().satisfies(m -> assertThat(m.isSupport()).isTrue());
        assertThat(view.memberships()).hasSize(1);
    }

    @Test
    void aStandardFeeIsNotASupportMembership() {
        service.request(USER, List.of(ordinary2026.getId()));

        assertThat(memberships.values()).singleElement().satisfies(m -> assertThat(m.isSupport()).isFalse());
    }

    @Test
    void thePayeeShowsTheBankgiroAndTheBankAccountWhenTheyAreSet() {
        final PaymentView view = service.request(USER, List.of(ordinary2026.getId()));

        assertThat(view.payee().bankAccount()).isEqualTo("1234-5, 678 901 234-5");
        assertThat(view.payee().bankgiro()).isEqualTo("");
    }
}
