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

import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.membership.Membership;
import nu.fgv.membership.server.membership.MembershipRepository;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * Lets another application ask whether an email address has an account here and whether it belongs to someone who is a
 * member, for a year (this year by default): for the application to decide what to offer, e.g. a member's price or
 * whether to ask for a payment at all.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ExternalLookupService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

    /**
     * @param exists  whether there is an account in Keycloak with this address
     * @param enabled whether it may log in
     */
    public record Account(boolean exists, boolean enabled) {
    }

    /**
     * @param kind     {@code STANDARD}, {@code FAMILY}, {@code SUPPORT} or {@code LIFETIME}
     * @param lifetime a life membership counts for the year because it started in that year or earlier
     * @param status   {@code PAID} or {@code WAIVED}
     */
    public record Active(int year, String kind, boolean lifetime, String status) {
    }

    /**
     * @param spexare whether the address is on a spexare in Spexregister
     * @param member  whether that spexare is a member for the year
     * @param active  what the membership is, when they are a member
     */
    public record Result(String email, int year, Account account, boolean spexare, boolean member, Active active) {
    }

    private final KeycloakAdmin keycloak;
    private final SpexregisterClient spexregister;
    private final MembershipRepository memberships;
    private final Clock clock;

    public ExternalLookupService(final KeycloakAdmin keycloak, final SpexregisterClient spexregister, final MembershipRepository memberships, final Clock clock) {
        this.keycloak = keycloak;
        this.spexregister = spexregister;
        this.memberships = memberships;
        this.clock = clock;
    }

    /**
     * @param year the year to ask about, or null for this one
     * @throws IllegalStateException when Spexregister cannot be reached, or several spexare have the address
     */
    @Transactional(readOnly = true)
    public Result lookup(final String email, final Integer year) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("A valid email address is needed");
        }

        final String address = email.strip().toLowerCase(Locale.ROOT);
        final int asked = year == null ? LocalDate.now(clock.withZone(ZONE)).getYear() : year;
        final Optional<KeycloakUser> user = keycloak.findUserByEmail(address);
        final Optional<Spexare> spexare = spexregister.byEmail(address);
        final Optional<Membership> active = spexare.flatMap(person -> memberships.findBySpexareId(person.id()).stream()
                .filter(m -> m.getType().equals(Membership.FGV) && m.counts() && (m.getYear() == asked || m.isLifetime() && m.getYear() <= asked))
                .max(Comparator.comparing(Membership::isLifetime).thenComparing(Membership::getYear)));

        return new Result(address, asked, new Account(user.isPresent(), user.map(KeycloakUser::enabled).orElse(false)), spexare.isPresent(), active.isPresent(),
                active.map(m -> new Active(m.getYear(), kindOf(m), m.isLifetime(), m.getStatus().name())).orElse(null));
    }

    private static String kindOf(final Membership membership) {
        if (membership.isLifetime()) {
            return "LIFETIME";
        }
        return membership.getFamilyId() != null ? "FAMILY" : membership.isSupport() ? "SUPPORT" : "STANDARD";
    }
}
