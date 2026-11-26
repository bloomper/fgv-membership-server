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

package nu.fgv.membership.server.external;

import nu.fgv.membership.server.FakeKeycloakAdmin;
import nu.fgv.membership.server.membership.Membership;
import nu.fgv.membership.server.membership.MembershipHow;
import nu.fgv.membership.server.membership.MembershipRepository;
import nu.fgv.membership.server.membership.MembershipStatus;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalLookupServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private final FakeKeycloakAdmin keycloak = new FakeKeycloakAdmin();
    private final SpexregisterClient spexregister = mock(SpexregisterClient.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final ExternalLookupService service = new ExternalLookupService(keycloak, spexregister, memberships, clock);

    @BeforeEach
    void setUp() {
        when(spexregister.byEmail("greta@example.org")).thenReturn(Optional.of(new Spexare("sp-1", "Greta", "Garbo", "greta@example.org")));
        when(spexregister.byEmail("ingen@example.org")).thenReturn(Optional.empty());
    }

    private Membership membership(final int year, final boolean lifetime, final boolean support, final String family, final MembershipStatus status) {
        final Membership membership = new Membership("sp-1", "FGV", year, clock.instant());

        membership.update(lifetime, status, MembershipHow.SWISH, null, clock.instant());
        membership.setSupport(support);
        membership.setFamilyId(family);
        return membership;
    }

    @Test
    void someoneUnknownHasNeitherAccountNorMembership() {
        final var result = service.lookup("Ingen@Example.org", null);

        assertThat(result.email()).isEqualTo("ingen@example.org");
        assertThat(result.year()).isEqualTo(2026);
        assertThat(result.account().exists()).isFalse();
        assertThat(result.spexare()).isFalse();
        assertThat(result.member()).isFalse();
        assertThat(result.active()).isNull();
    }

    @Test
    void anAccountWithoutAMembershipIsJustAnAccount() {
        keycloak.addUser("greta@example.org", false, true);

        final var result = service.lookup("greta@example.org", null);

        assertThat(result.account().exists()).isTrue();
        assertThat(result.account().enabled()).isTrue();
        assertThat(result.spexare()).isTrue();
        assertThat(result.member()).isFalse();
    }

    @Test
    void aPaidMembershipForTheYearMakesAMemberAndOneForAnotherYearDoesNot() {
        when(memberships.findBySpexareId("sp-1")).thenReturn(List.of(membership(2026, false, false, null, MembershipStatus.PAID), membership(2025, false, false, null, MembershipStatus.PAID)));

        assertThat(service.lookup("greta@example.org", null).active()).isEqualTo(new ExternalLookupService.Active(2026, "STANDARD", false, "PAID"));
        assertThat(service.lookup("greta@example.org", 2025).member()).isTrue();
        assertThat(service.lookup("greta@example.org", 2024).member()).isFalse();
    }

    @Test
    void aMembershipThatIsOnlyAskedForOrReportedIsNotAMembership() {
        when(memberships.findBySpexareId("sp-1")).thenReturn(List.of(membership(2026, false, false, null, MembershipStatus.PENDING), membership(2026, false, false, null, MembershipStatus.REPORTED)));

        assertThat(service.lookup("greta@example.org", null).member()).isFalse();
    }

    @Test
    void aWaivedMembershipCountsAndTheKindIsSaid() {
        when(memberships.findBySpexareId("sp-1")).thenReturn(List.of(membership(2026, false, true, null, MembershipStatus.WAIVED)));

        assertThat(service.lookup("greta@example.org", null).active()).isEqualTo(new ExternalLookupService.Active(2026, "SUPPORT", false, "WAIVED"));
    }

    @Test
    void aFamilyMembershipAndALifeMembershipAreNamedAsSuchAndLifeCountsInLaterYears() {
        when(memberships.findBySpexareId("sp-1")).thenReturn(List.of(membership(2026, false, false, "fam", MembershipStatus.PAID)));
        assertThat(service.lookup("greta@example.org", null).active().kind()).isEqualTo("FAMILY");

        when(memberships.findBySpexareId("sp-1")).thenReturn(List.of(membership(2019, true, false, null, MembershipStatus.PAID)));
        assertThat(service.lookup("greta@example.org", null).active()).isEqualTo(new ExternalLookupService.Active(2019, "LIFETIME", true, "PAID"));
        assertThat(service.lookup("greta@example.org", 2018).member()).isFalse();
    }

    @Test
    void aLifeMembershipWinsOverAYearlyOne() {
        when(memberships.findBySpexareId("sp-1")).thenReturn(List.of(membership(2026, false, false, null, MembershipStatus.PAID), membership(2019, true, false, null, MembershipStatus.PAID)));

        assertThat(service.lookup("greta@example.org", null).active().kind()).isEqualTo("LIFETIME");
    }

    @Test
    void aDisabledAccountIsSaidToBeSo() {
        final var user = keycloak.addUser("greta@example.org", false, true);

        keycloak.setEnabled(user.id(), false);

        assertThat(service.lookup("greta@example.org", null).account().enabled()).isFalse();
    }

    @Test
    void anAddressThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> service.lookup("nope", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.lookup(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
