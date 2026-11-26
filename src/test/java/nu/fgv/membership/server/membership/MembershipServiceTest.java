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

package nu.fgv.membership.server.membership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipServiceTest {

    private static final String SPEXARE = "0197d6a0-0000-7000-8000-000000000001";

    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final MembershipChangeRepository changes = mock(MembershipChangeRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);

    private MembershipService service;

    @BeforeEach
    void setUp() {
        service = new MembershipService(memberships, changes, events, clock);
        when(memberships.findBySpexareIdAndTypeAndYear(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.empty());
    }

    @Test
    void aPaidMembershipGoesToTheFeed() {
        service.record(SPEXARE, "FGV", 2026, false, MembershipStatus.PAID, MembershipHow.SWISH, null);

        final ArgumentCaptor<MembershipChange> change = ArgumentCaptor.forClass(MembershipChange.class);

        verify(changes).save(change.capture());
        assertThat(change.getValue().getKind()).isEqualTo(ChangeKind.UPSERT);
        assertThat(change.getValue().getYear()).isEqualTo(2026);
        verify(events).publishEvent(any(MembershipsChanged.class));
    }

    @Test
    void aMembershipThatDoesNotCountYetIsNotInTheFeed() {
        service.record(SPEXARE, "FGV", 2026, false, MembershipStatus.REPORTED, null, null);

        verify(changes, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void refundingAPaidMembershipRemovesItFromTheFeed() {
        final Membership paid = new Membership(SPEXARE, "FGV", 2026, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.SWISH, null, clock.instant());
        when(memberships.findBySpexareIdAndTypeAndYear(SPEXARE, "FGV", 2026)).thenReturn(Optional.of(paid));

        service.record(SPEXARE, "FGV", 2026, false, MembershipStatus.CANCELLED, MembershipHow.SWISH, "refunded");

        final ArgumentCaptor<MembershipChange> change = ArgumentCaptor.forClass(MembershipChange.class);

        verify(changes).save(change.capture());
        assertThat(change.getValue().getKind()).isEqualTo(ChangeKind.DELETE);
    }

    @Test
    void savingTheSameThingAgainChangesNothing() {
        final Membership paid = new Membership(SPEXARE, "FGV", 2026, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.SWISH, null, clock.instant());
        when(memberships.findBySpexareIdAndTypeAndYear(SPEXARE, "FGV", 2026)).thenReturn(Optional.of(paid));

        service.record(SPEXARE, "FGV", 2026, false, MembershipStatus.PAID, MembershipHow.BANK_TRANSFER, "note");

        verify(changes, never()).save(any());
    }

    @Test
    void makingAMembershipLifelongIsAChange() {
        final Membership paid = new Membership(SPEXARE, "FGV", 2019, clock.instant());

        paid.update(false, MembershipStatus.PAID, MembershipHow.MIGRATED, null, clock.instant());
        when(memberships.findBySpexareIdAndTypeAndYear(SPEXARE, "FGV", 2019)).thenReturn(Optional.of(paid));

        service.record(SPEXARE, "FGV", 2019, true, MembershipStatus.PAID, MembershipHow.MIGRATED, null);

        final ArgumentCaptor<MembershipChange> change = ArgumentCaptor.forClass(MembershipChange.class);

        verify(changes).save(change.capture());
        assertThat(change.getValue().isLifetime()).isTrue();
    }
}
