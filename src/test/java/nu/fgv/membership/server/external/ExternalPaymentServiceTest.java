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
import nu.fgv.membership.server.access.AccessChangeRepository;
import nu.fgv.membership.server.access.AccessService;
import nu.fgv.membership.server.access.ApplicationCatalog;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.mail.AccessMails;
import nu.fgv.membership.server.payment.PaymentMails;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentStatus;
import nu.fgv.membership.server.payment.PaymentView;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalPaymentServiceTest {

    private static final String SHOP = "shop-service";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private final FakeKeycloakAdmin keycloak = new FakeKeycloakAdmin()
            .withClient("fgv-membership", "Medlemskap", "https://medlem.example")
            .withClient("fgv-spexregister", "Spexregister", "https://register.example")
            .withClient("fgv-revival", "Nyuppsättning", "https://revival.example");
    private final Map<DefaultAccess.Key, DefaultAccess> defaultRows = new HashMap<>();
    private final Map<String, ExternalPayment> told = new HashMap<>();
    private final DefaultAccessRepository defaultRepository = mock(DefaultAccessRepository.class);
    private final AppSettingRepository settings = mock(AppSettingRepository.class);
    private final ExternalPaymentRepository externals = mock(ExternalPaymentRepository.class);
    private final SpexregisterClient spexregister = mock(SpexregisterClient.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final PaymentMails mails = mock(PaymentMails.class);

    private ExternalPaymentService service;
    private DefaultAccessService defaults;

    @BeforeEach
    void setUp() {
        final MembershipProperties properties = new MembershipProperties("https://medlem.example",
                new MembershipProperties.Keycloak("http://kc", "fgv", "svc", "secret", "pending-approval", Duration.ofSeconds(5)), null, null, null,
                List.of(new MembershipProperties.Application("fgv-membership", List.of("USER", "BOARD", "TREASURER", "ADMIN"), true),
                        new MembershipProperties.Application("fgv-spexregister", List.of("USER", "EDITOR", "ADMIN"), false),
                        new MembershipProperties.Application("fgv-revival", List.of("USER", "OBSERVER", "SUPER_ADMIN"), false)), null);
        final ApplicationCatalog catalog = new ApplicationCatalog(properties, keycloak);
        final AccessService access = new AccessService(keycloak, catalog, mock(AccessChangeRepository.class), mock(AccessMails.class), properties);

        when(defaultRepository.findByProfile(any())).thenAnswer(call -> defaultRows.values().stream().filter(row -> row.getProfile() == call.getArgument(0)).toList());
        when(defaultRepository.save(any(DefaultAccess.class))).thenAnswer(call -> {
            final DefaultAccess row = call.getArgument(0);

            defaultRows.put(new DefaultAccess.Key(row.getProfile(), row.getClientId()), row);
            return row;
        });
        when(defaultRepository.findById(any(DefaultAccess.Key.class))).thenAnswer(call -> Optional.ofNullable(defaultRows.get(call.<DefaultAccess.Key>getArgument(0))));
        org.mockito.Mockito.doAnswer(call -> defaultRows.remove(call.<DefaultAccess.Key>getArgument(0))).when(defaultRepository).deleteById(any(DefaultAccess.Key.class));
        final Map<String, Boolean> seeded = new HashMap<>();

        when(settings.existsById(anyString())).thenAnswer(call -> seeded.containsKey(call.<String>getArgument(0)));
        when(settings.save(any(AppSetting.class))).thenAnswer(call -> {
            seeded.put(call.<AppSetting>getArgument(0).getName(), true);
            return call.getArgument(0);
        });
        defaults = new DefaultAccessService(defaultRepository, settings, catalog, access, properties, List.of("fgv-revival"), List.of("fgv-spexregister"));

        when(externals.findByExternalRef(anyString())).thenAnswer(call -> Optional.ofNullable(told.get(call.<String>getArgument(0))));
        when(externals.save(any(ExternalPayment.class))).thenAnswer(call -> call.getArgument(0));
        when(spexregister.byEmail(anyString())).thenReturn(Optional.empty());
        when(spexregister.create(anyString(), anyString(), anyString())).thenReturn(new Spexare("sp-1", "Greta", "Garbo", "greta@example.org"));
        when(payments.recordExternal(anyString(), anyString(), anyInt(), anyBoolean(), anyBoolean(), anyInt(), any(), anyString()))
                .thenReturn(Optional.of(new PaymentView("req-1", "FGV26ABCD", 200, PaymentStatus.MATCHED, clock.instant(), null, null, "sp-1", null,
                        List.of(), null, null, 200, null, false, false)));

        service = new ExternalPaymentService(externals, spexregister, keycloak, defaults, payments, mails, mock(PlatformTransactionManager.class), clock);
    }

    private static ExternalPaymentRequest request(final String email, final String reference) {
        return new ExternalPaymentRequest(email, "Greta", "Garbo", 2026, 200, LocalDate.of(2026, 11, 25), reference, false, null);
    }

    @Test
    void aPaymentFromSomeoneNewCreatesTheSpexareTheAccountAndTheMembership() {
        final var result = service.record(SHOP, request("Greta@Example.org", "order-1"));

        assertThat(result.status()).isEqualTo(ExternalPaymentService.Status.RECORDED);
        assertThat(result.accountCreated()).isTrue();
        verify(spexregister).create("Greta", "Garbo", "greta@example.org");

        final KeycloakUser user = keycloak.findUserByEmail("greta@example.org").orElseThrow();

        assertThat(user.emailVerified()).isTrue();
        verify(payments).recordExternal(eq(SHOP), eq("sp-1"), eq(2026), eq(false), eq(false), eq(200), eq(LocalDate.of(2026, 11, 25)), eq("Online: order-1"));
        verify(mails).accountCreated(user.id(), "greta@example.org");
    }

    @Test
    void theNewAccountGetsTheDefaultAccessAndALinkToChooseAPassword() {
        service.record(SHOP, request("greta@example.org", "order-1"));

        final KeycloakUser user = keycloak.findUserByEmail("greta@example.org").orElseThrow();

        assertThat(keycloak.rolesOf(user.id(), "fgv-membership")).containsExactly("USER");
        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).containsExactly("USER");
        assertThat(keycloak.rolesOf(user.id(), "fgv-revival")).isEmpty();
        assertThat(keycloak.isMemberOfGroup(user.id(), "pending-approval")).isFalse();
        assertThat(keycloak.passwordEmails).contains(user.id());
    }

    @Test
    void whatAdminsChangeInTheDefaultsIsWhatNewAccountsGet() {
        defaults.set("fgv-spexregister", "EDITOR");
        defaults.set("fgv-membership", null);
        defaults.set("fgv-revival", "USER");

        service.record(SHOP, request("greta@example.org", "order-1"));

        final KeycloakUser user = keycloak.findUserByEmail("greta@example.org").orElseThrow();

        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).containsExactlyInAnyOrder("USER", "EDITOR");
        assertThat(keycloak.rolesOf(user.id(), "fgv-membership")).isEmpty();
        assertThat(keycloak.rolesOf(user.id(), "fgv-revival")).containsExactly("USER");
    }

    @Test
    void theDefaultsAreWrittenDownOnceSoAnAdminsRemovalStays() {
        assertThat(defaults.all()).filteredOn(entry -> entry.role() != null).extracting(DefaultAccessService.Entry::clientId)
                .containsExactlyInAnyOrder("fgv-membership", "fgv-spexregister");

        defaults.set("fgv-spexregister", null);

        assertThat(defaults.all()).filteredOn(entry -> entry.clientId().equals("fgv-spexregister")).singleElement()
                .satisfies(entry -> assertThat(entry.role()).isNull());
    }

    @Test
    void aRoleThatIsNotOnTheLadderCannotBeADefault() {
        assertThatThrownBy(() -> defaults.set("fgv-spexregister", "SYNC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.set("fgv-unknown", "USER")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExistingAccountAndSpexareAreLeftAlone() {
        final KeycloakUser existing = keycloak.addUser("greta@example.org", false, true);

        when(spexregister.byEmail("greta@example.org")).thenReturn(Optional.of(new Spexare("sp-1", "Greta", "Garbo", "greta@example.org")));

        final var result = service.record(SHOP, request("greta@example.org", "order-1"));

        assertThat(result.accountCreated()).isFalse();
        assertThat(keycloak.rolesOf(existing.id(), "fgv-spexregister")).isEmpty();
        assertThat(keycloak.passwordEmails).isEmpty();
        verify(spexregister, never()).create(anyString(), anyString(), anyString());
        verify(mails, never()).accountCreated(anyString(), anyString());
    }

    @Test
    void tellingTheSameOrderTwiceChangesNothing() {
        told.put("order-1", new ExternalPayment(SHOP, "order-1", "req-1", "RECORDED", true, clock.instant()));

        final var result = service.record(SHOP, request("greta@example.org", "order-1"));

        assertThat(result.status()).isEqualTo(ExternalPaymentService.Status.DUPLICATE);
        verify(payments, never()).recordExternal(anyString(), anyString(), anyInt(), anyBoolean(), anyBoolean(), anyInt(), any(), anyString());
        assertThat(keycloak.users.values()).noneMatch(user -> "greta@example.org".equals(user.email()));
    }

    @Test
    void someoneWhoIsAlreadyAMemberThatYearIsReportedAndNothingIsRecorded() {
        when(payments.recordExternal(anyString(), anyString(), anyInt(), anyBoolean(), anyBoolean(), anyInt(), any(), anyString())).thenReturn(Optional.empty());

        final var result = service.record(SHOP, request("greta@example.org", "order-2"));

        assertThat(result.status()).isEqualTo(ExternalPaymentService.Status.ALREADY_MEMBER);
        assertThat(result.reference()).isNull();
    }

    @Test
    void anAccountThatCannotBeFinishedIsRemovedSoARetryStartsClean() {
        final var failing = mock(PaymentMails.class);

        doThrow(new IllegalStateException("mail down")).when(failing).accountCreated(anyString(), anyString());
        service = new ExternalPaymentService(externals, spexregister, keycloak, defaults, payments, failing, mock(PlatformTransactionManager.class), clock);

        assertThatThrownBy(() -> service.record(SHOP, request("greta@example.org", "order-1"))).isInstanceOf(IllegalStateException.class);
        assertThat(keycloak.findUserByEmail("greta@example.org")).isEmpty();
    }

    @Test
    void invalidRequestsAreRefusedBeforeAnythingIsDone() {
        assertThatThrownBy(() -> service.record(SHOP, request("not-an-email", "order-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(SHOP, request("greta@example.org", " "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(SHOP, new ExternalPaymentRequest("greta@example.org", "G", "G", 1800, 200, null, "o", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(SHOP, new ExternalPaymentRequest("greta@example.org", "", "G", 2026, 200, null, "o", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(spexregister, never()).create(anyString(), anyString(), anyString());
    }

    @Test
    void aSupportMemberGetsTheSupportAccessAndNotTheMemberAccess() {
        final var result = service.record(SHOP, new ExternalPaymentRequest("stod@example.org", "Stig", "Stod", 2026, 100, LocalDate.of(2026, 11, 25), "order-9", false,
                nu.fgv.membership.server.payment.FeeKind.SUPPORT));

        assertThat(result.accountCreated()).isTrue();

        final KeycloakUser user = keycloak.findUserByEmail("stod@example.org").orElseThrow();

        assertThat(keycloak.rolesOf(user.id(), "fgv-spexregister")).containsExactly("USER");
        assertThat(keycloak.rolesOf(user.id(), "fgv-membership")).isEmpty();
        verify(payments).recordExternal(eq(SHOP), eq("sp-1"), eq(2026), eq(false), eq(true), eq(100), any(), anyString());
    }

    @Test
    void theSupportAccessIsKeptSeparatelyFromTheMemberAccess() {
        defaults.set(nu.fgv.membership.server.payment.AccessProfile.SUPPORT, "fgv-spexregister", null);
        defaults.set(nu.fgv.membership.server.payment.AccessProfile.SUPPORT, "fgv-membership", "USER");

        assertThat(defaults.all(nu.fgv.membership.server.payment.AccessProfile.SUPPORT)).filteredOn(entry -> entry.role() != null)
                .extracting(DefaultAccessService.Entry::clientId).containsExactly("fgv-membership");
        assertThat(defaults.all()).filteredOn(entry -> entry.role() != null).extracting(DefaultAccessService.Entry::clientId)
                .containsExactlyInAnyOrder("fgv-membership", "fgv-spexregister");
    }

    @Test
    void aLifeMembershipFromTheShopIsStillSold() {
        service.record(SHOP, new ExternalPaymentRequest("liv@example.org", "Liv", "Livs", 2026, 2000, LocalDate.of(2026, 11, 25), "order-10", true, null));

        verify(payments).recordExternal(eq(SHOP), eq("sp-1"), eq(2026), eq(true), eq(false), eq(2000), any(), anyString());
    }

    @Test
    void aFamilyMembershipIsNotSoldInTheShop() {
        assertThatThrownBy(() -> service.record(SHOP, new ExternalPaymentRequest("fam@example.org", "Fam", "Ilj", 2026, 350, null, "order-11", false,
                nu.fgv.membership.server.payment.FeeKind.FAMILY))).isInstanceOf(IllegalArgumentException.class);
        verify(spexregister, never()).create(anyString(), anyString(), anyString());
    }
}
