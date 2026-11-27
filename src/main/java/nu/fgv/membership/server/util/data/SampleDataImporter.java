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

package nu.fgv.membership.server.util.data;

import nu.fgv.membership.server.access.AccessChange;
import nu.fgv.membership.server.access.AccessChangeRepository;
import nu.fgv.membership.server.application.Application;
import nu.fgv.membership.server.application.ApplicationRepository;
import nu.fgv.membership.server.bank.BankFormat;
import nu.fgv.membership.server.bank.BankFormatRepository;
import nu.fgv.membership.server.bank.BankTransaction;
import nu.fgv.membership.server.bank.BankTransactionRepository;
import nu.fgv.membership.server.board.AuditEntry;
import nu.fgv.membership.server.board.AuditEntryRepository;
import nu.fgv.membership.server.board.PersonalLink;
import nu.fgv.membership.server.board.PersonalLinkRepository;
import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import nu.fgv.membership.server.membership.Membership;
import nu.fgv.membership.server.membership.MembershipHow;
import nu.fgv.membership.server.membership.MembershipService;
import nu.fgv.membership.server.membership.MembershipStatus;
import nu.fgv.membership.server.payment.FeeKind;
import nu.fgv.membership.server.payment.FeeLevel;
import nu.fgv.membership.server.payment.FeeLevelRepository;
import nu.fgv.membership.server.payment.Payment;
import nu.fgv.membership.server.payment.PaymentHow;
import nu.fgv.membership.server.payment.PaymentReference;
import nu.fgv.membership.server.payment.PaymentRepository;
import nu.fgv.membership.server.payment.PaymentRequest;
import nu.fgv.membership.server.payment.PaymentRequestRepository;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import nu.fgv.membership.server.registration.Registration;
import nu.fgv.membership.server.registration.RegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Replaces everything with sample data at every start while {@code membership.data.import-sample-data}
 * ({@code API_IMPORT_SAMPLE_DATA}) is set (spec 5.10); refuses to run in the {@code production} profile.
 *
 * <p>Like the other FGV applications it uses fgv-infra-deploy's sample users (bin/seed-users.sh) by their fixed ids,
 * and never changes their accounts or roles. The spexare are Spexregister's sample spexare, found through the
 * {@code member01..45} users it has linked to them, so Spexregister must run with its sample data. The only thing done in
 * Keycloak is two accounts waiting for approval, which the registration cases of the admin pages need; they are made when
 * missing. Two sample bank statements, one per bank format, are written to {@code membership.data.statement-dir} for
 * uploading by hand, with references of requests that are still open.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SampleDataImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataImporter.class);

    // fgv-infra-deploy's sample-users.tsv
    static final String ADMIN = "00000000-0000-4000-8000-000000000001";
    static final String EDITOR = "00000000-0000-4000-8000-000000000002";
    static final String USER = "00000000-0000-4000-8000-000000000003";
    static final String NO_ACCESS = "00000000-0000-4000-8000-000000000005";
    static final String TREASURER = "00000000-0000-4000-8000-000000000006";
    private static final String MEMBER_USER = "00000000-0000-4000-8000-%012d";
    private static final int MEMBER_USERS = 45;
    private static final int MIN_SPEXARE = 12;
    private static final String FGV = Membership.FGV;
    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

    static final String OPEN_TOKEN = "sample-personal-link-open";
    static final String REPORTED_TOKEN = "sample-personal-link-reported";

    // Indexes of the spexare that get a special case instead of the pattern of their number
    private static final int SUPPORT = 9;
    private static final int FAMILY_PAYER = 17;
    private static final int FAMILY_MEMBER_1 = 18;
    private static final int FAMILY_MEMBER_2 = 19;
    private static final int REVIVAL_1 = 30;
    private static final int REVIVAL_2 = 31;

    private final boolean enabled;
    private final Path statementDir;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MembershipProperties properties;
    private final SpexregisterClient spexregister;
    private final KeycloakAdmin keycloak;
    private final MembershipService memberships;
    private final FeeLevelRepository fees;
    private final PaymentRequestRepository requests;
    private final PaymentRepository payments;
    private final PersonalLinkRepository links;
    private final BankFormatRepository formats;
    private final BankTransactionRepository transactions;
    private final ApplicationRepository applications;
    private final RegistrationRepository registrations;
    private final AuditEntryRepository audit;
    private final AccessChangeRepository accessChanges;

    @SuppressWarnings("java:S107")
    public SampleDataImporter(@Value("${membership.data.import-sample-data:false}") final boolean enabled,
                              @Value("${membership.data.statement-dir:./sample-data}") final Path statementDir,
                              final Environment environment, final JdbcTemplate jdbc, final TransactionTemplate transaction,
                              final Clock clock, final MembershipProperties properties, final SpexregisterClient spexregister,
                              final KeycloakAdmin keycloak, final MembershipService memberships, final FeeLevelRepository fees,
                              final PaymentRequestRepository requests, final PaymentRepository payments,
                              final PersonalLinkRepository links, final BankFormatRepository formats,
                              final BankTransactionRepository transactions, final ApplicationRepository applications,
                              final RegistrationRepository registrations, final AuditEntryRepository audit,
                              final AccessChangeRepository accessChanges) {
        this.enabled = enabled;
        this.statementDir = statementDir;
        this.environment = environment;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.properties = properties;
        this.spexregister = spexregister;
        this.keycloak = keycloak;
        this.memberships = memberships;
        this.fees = fees;
        this.requests = requests;
        this.payments = payments;
        this.links = links;
        this.formats = formats;
        this.transactions = transactions;
        this.applications = applications;
        this.registrations = registrations;
        this.audit = audit;
        this.accessChanges = accessChanges;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("production"))) {
            throw new IllegalStateException("Refusing to import sample data in the production profile");
        }
        importSampleData();
    }

    public void importSampleData() {
        final long started = System.nanoTime();
        final List<Spexare> spexare = sampleSpexare();

        log.info("Importing sample data for {} spexare", spexare.size());
        final List<Statement> statements = transaction.execute(status -> {
            clear();
            return create(spexare);
        });

        writeStatements(statements);
        pendingAccounts();
        log.info("Imported sample data in {} ms", Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    /**
     * Spexregister's sample spexare, by the sample users it has linked to them; Keycloak is not asked.
     */
    private List<Spexare> sampleSpexare() {
        final List<Spexare> found = new ArrayList<>();

        for (int i = 1; i <= MEMBER_USERS; i++) {
            spexregister.forUser(MEMBER_USER.formatted(1000 + i)).ifPresent(found::add);
        }
        if (found.size() < MIN_SPEXARE) {
            throw new IllegalStateException("Spexregister has only " + found.size() + " spexare linked to the sample users"
                    + " member01..member45; import its sample data first (and run fgv-infra-deploy's bin/seed-users.sh)");
        }
        return found;
    }

    private void clear() {
        // Children first. Settings (default access, app_setting) are configuration and stay.
        for (final String table : List.of("payment", "bank_transaction", "bank_format", "personal_link", "payment_request_membership",
                "payment_request", "reminder_email", "reminder_campaign", "reminder_opt_out", "membership_change", "membership",
                "sync_consumer", "fee_level", "external_payment", "audit_entry", "application", "registration", "access_change")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private record Statement(BankFormat format, List<String[]> rows) {
    }

    private List<Statement> create(final List<Spexare> spexare) {
        final Instant now = clock.instant();
        final int year = Year.now(clock.withZone(ZONE)).getValue();
        createFees(year);
        final List<PaymentRequest> open = new ArrayList<>();
        final List<PaymentRequest> reported = new ArrayList<>();
        final List<Spexare> openPayers = new ArrayList<>();

        // History: older years, as migrated from Spexregister and then paid in the usual ways
        for (int i = 0; i < spexare.size(); i++) {
            final String id = spexare.get(i).id();

            if (i == 0) {
                memberships.record(id, FGV, year - 3, true, MembershipStatus.PAID, MembershipHow.CASH, "Livstidsmedlem");
                continue;
            }
            if (i == 1) {
                memberships.record(id, FGV, year - 1, true, MembershipStatus.PAID, MembershipHow.SWISH, null);
                continue;
            }
            for (int y = year - 3; y < year; y++) {
                if ((i + y) % 3 != 0) {
                    memberships.record(id, FGV, y, false, MembershipStatus.PAID, y < year - 1 ? MembershipHow.MIGRATED : MembershipHow.SWISH, null);
                }
            }
        }

        final FeeLevel std = feeOf(FeeKind.STANDARD, year);
        final FeeLevel fam = feeOf(FeeKind.FAMILY, year);
        final FeeLevel sup = feeOf(FeeKind.SUPPORT, year);

        // This year, by pattern
        for (int i = 2; i < spexare.size(); i++) {
            final Spexare person = spexare.get(i);

            if (i == SUPPORT) {
                paid(spexare, List.of(i), sup, PaymentHow.SWISH, 12, true);
            } else if (i == FAMILY_PAYER) {
                paid(spexare, List.of(i, FAMILY_MEMBER_1, FAMILY_MEMBER_2), fam, PaymentHow.BANK_TRANSFER, 9, false);
            } else if (i == FAMILY_MEMBER_1 || i == FAMILY_MEMBER_2) {
                continue;
            } else if (i == REVIVAL_1) {
                revival(spexare, List.of(REVIVAL_1, REVIVAL_2), std);
            } else if (i == REVIVAL_2) {
                continue;
            } else {
                switch (i % 8) {
                    case 2 -> paid(spexare, List.of(i), std, PaymentHow.SWISH, 20 - i % 5, false);
                    case 3 -> bankTransfer(person, std);
                    case 4 -> paid(spexare, List.of(i), std, PaymentHow.CASH, 6, false);
                    case 5 -> {
                        final PaymentRequest request = request(List.of(membership(person, year, MembershipStatus.PENDING, null)), person.id(), std.getAmount(), 5 + i % 3);

                        open.add(request);
                        openPayers.add(person);
                    }
                    case 6 -> {
                        final PaymentRequest request = request(List.of(membership(person, year, MembershipStatus.REPORTED, null)), person.id(), std.getAmount(), 3);

                        request.report(person.name(), now.minus(Duration.ofDays(1)));
                        requests.save(request);
                        reported.add(request);
                    }
                    case 7 -> memberships.record(person.id(), FGV, year, false, MembershipStatus.WAIVED, MembershipHow.WAIVED, "Hedersmedlem utan avgift");
                    case 0 -> {
                        final PaymentRequest request = request(List.of(membership(person, year, MembershipStatus.CANCELLED, null)), person.id(), std.getAmount(), 40);

                        request.cancel();
                        requests.save(request);
                    }
                    default -> {
                        // 1: a member in earlier years who has not renewed
                    }
                }
            }
        }

        if (!open.isEmpty()) {
            links.save(new PersonalLink(open.get(0).getPk(), hash(OPEN_TOKEN), endOfNextYear(year), now));
        }
        if (!reported.isEmpty()) {
            links.save(new PersonalLink(reported.get(0).getPk(), hash(REPORTED_TOKEN), endOfNextYear(year), now));
        }

        applications();
        auditTrail(spexare, now);
        return bankStatements(open, openPayers, year);
    }

    private void createFees(final int year) {
        for (int y = year - 3; y <= year + 1; y++) {
            final boolean retired = y < year - 1;

            for (final FeeKind kind : List.of(FeeKind.STANDARD, FeeKind.FAMILY, FeeKind.SUPPORT)) {
                final FeeLevel level = new FeeLevel(FGV, y, kind, name(kind, y, "sv"), name(kind, y, "en"), amount(kind), clock.instant());

                level.setRetired(retired);
                fees.save(level);
            }
        }
        fees.save(new FeeLevel(FGV, null, FeeKind.LIFETIME, "Livstidsmedlem", "Life member", 1500, clock.instant()));
    }

    private static String name(final FeeKind kind, final int year, final String language) {
        final boolean sv = "sv".equals(language);

        return switch (kind) {
            case FAMILY -> (sv ? "Familjemedlem " : "Family member ") + year;
            case SUPPORT -> (sv ? "Stödmedlem " : "Supporting member ") + year;
            default -> (sv ? "Medlem " : "Member ") + year;
        };
    }

    private static int amount(final FeeKind kind) {
        return switch (kind) {
            case FAMILY -> 250;
            case SUPPORT -> 100;
            default -> 150;
        };
    }

    private FeeLevel feeOf(final FeeKind kind, final int year) {
        return fees.findAll().stream().filter(level -> level.getKind() == kind && level.getYear() != null && level.getYear() == year)
                .findFirst().orElseThrow();
    }

    private Membership membership(final Spexare person, final int year, final MembershipStatus status, final MembershipHow how) {
        return memberships.record(person.id(), FGV, year, false, status, how, null);
    }

    private PaymentRequest request(final List<Membership> members, final String spexareId, final int amount, final int daysAgo) {
        final Instant created = clock.instant().minus(Duration.ofDays(daysAgo));
        final PaymentRequest request = new PaymentRequest(PaymentReference.generate(Year.now(clock.withZone(ZONE))), spexareId, amount,
                spexareId, ids(members), created);

        return requests.save(request);
    }

    private static Set<String> ids(final List<Membership> members) {
        final Set<String> ids = new LinkedHashSet<>();

        members.forEach(member -> ids.add(member.getId()));
        return ids;
    }

    /**
     * A request that has been paid by hand: one payer, and for a family the payer's whole family.
     */
    private void paid(final List<Spexare> spexare, final List<Integer> who, final FeeLevel level, final PaymentHow how, final int daysAgo,
                      final boolean support) {
        final int year = level.getYear();
        final String familyId = who.size() > 1 ? java.util.UUID.randomUUID().toString() : null;
        final List<Membership> members = new ArrayList<>();

        for (final int index : who) {
            if (index >= spexare.size()) {
                continue;
            }

            final Membership member = memberships.record(spexare.get(index).id(), FGV, year, false, MembershipStatus.PAID, membershipHow(how), null);

            member.setSupport(support);
            member.setFamilyId(familyId);
            members.add(member);
        }

        final Spexare payer = spexare.get(who.get(0));
        final PaymentRequest request = request(members, payer.id(), level.getAmount(), daysAgo);

        if (familyId != null) {
            request.family(familyId, "Min familj");
        }
        request.matched();
        requests.save(request);
        payments.save(new Payment(request.getPk(), LocalDate.now(clock.withZone(ZONE)).minusDays(daysAgo), level.getAmount(), how, TREASURER,
                how == PaymentHow.CASH ? "Betalade kontant på ett möte" : null, clock.instant().minus(Duration.ofDays(daysAgo))));
    }

    private static MembershipHow membershipHow(final PaymentHow how) {
        return switch (how) {
            case SWISH -> MembershipHow.SWISH;
            case BANK_TRANSFER -> MembershipHow.BANK_TRANSFER;
            case CASH -> MembershipHow.CASH;
            case ONLINE -> MembershipHow.ONLINE;
        };
    }

    /**
     * Paid by bank transfer, found in an imported statement.
     */
    private void bankTransfer(final Spexare person, final FeeLevel level) {
        final int days = 10;
        final Membership member = memberships.record(person.id(), FGV, level.getYear(), false, MembershipStatus.PAID, MembershipHow.BANK_TRANSFER, null);
        final PaymentRequest request = request(List.of(member), person.id(), level.getAmount(), days + 2);
        final LocalDate booked = LocalDate.now(clock.withZone(ZONE)).minusDays(days);
        final BankTransaction transaction = new BankTransaction(booked, level.getAmount() * 100L, request.getReference() + " medlemsavgift",
                person.name(), hash("sample|" + request.getReference()), "sample-statement.csv", TREASURER, clock.instant().minus(Duration.ofDays(days)));

        transaction.matched();
        transactions.save(transaction);
        request.matched();
        requests.save(request);
        payments.save(new Payment(request.getPk(), booked, level.getAmount(), PaymentHow.BANK_TRANSFER, TREASURER, "Bank: sample-statement.csv",
                clock.instant().minus(Duration.ofDays(days))).fromTransaction(transaction.getPk()));
    }

    /**
     * A revival paid for its participants in one request.
     */
    private void revival(final List<Spexare> spexare, final List<Integer> who, final FeeLevel level) {
        final List<Membership> members = new ArrayList<>();

        for (final int index : who) {
            if (index < spexare.size()) {
                members.add(memberships.record(spexare.get(index).id(), FGV, level.getYear(), false, MembershipStatus.PAID, MembershipHow.REVIVAL, null));
            }
        }

        final PaymentRequest request = PaymentRequest.forRevival(PaymentReference.generate(Year.now(clock.withZone(ZONE))), "Sample Revival",
                level.getAmount() * members.size(), TREASURER, ids(members), clock.instant().minus(Duration.ofDays(15)));

        request.matched();
        requests.save(request);
        payments.save(new Payment(request.getPk(), LocalDate.now(clock.withZone(ZONE)).minusDays(14), request.getAmount(), PaymentHow.ONLINE,
                TREASURER, "Betalat av Sample Revival", clock.instant().minus(Duration.ofDays(14))));
    }

    private void applications() {
        applications.save(new Application(NO_ACCESS, "No Access", "Jag var med i Chalmersspexet 1998 och vill bli medlem.", clock.instant().minus(Duration.ofDays(2))));
        final Application rejected = new Application(USER, "User Exempel", "Kan jag få medlemskap utan att ha varit med i ett spex?", clock.instant().minus(Duration.ofDays(30)));

        rejected.reject(ADMIN, "Medlemskap är för dem som har medverkat i ett spex.", clock.instant().minus(Duration.ofDays(28)));
        applications.save(rejected);
    }

    private void auditTrail(final List<Spexare> spexare, final Instant now) {
        audit.save(new AuditEntry(TREASURER, "PAYMENT", spexare.get(2).id(), "Kontant betalning", now.minus(Duration.ofDays(6))));
        audit.save(new AuditEntry(ADMIN, "FEE", null, "Avgifter för nästa år", now.minus(Duration.ofDays(20))));
        accessChanges.save(new AccessChange(EDITOR, ADMIN, "ACCESS", "fgv-membership", "USER", "USER,BOARD"));
    }

    private List<Statement> bankStatements(final List<PaymentRequest> open, final List<Spexare> payers, final int year) {
        final BankFormat semicolon = new BankFormat(clock.instant());

        semicolon.update("Sample bank (semikolon, UTF-8)", "UTF-8", ";", 1, 1, 2, 3, 4, "yyyy-MM-dd", true);
        formats.save(semicolon);

        final BankFormat comma = new BankFormat(clock.instant());

        comma.update("Sample bank 2 (komma, ISO-8859-1)", "ISO-8859-1", ",", 1, 2, 3, 4, 1, "dd/MM/yyyy", false);
        formats.save(comma);

        final LocalDate today = LocalDate.now(clock.withZone(ZONE));
        final List<String[]> first = new ArrayList<>();
        final List<String[]> second = new ArrayList<>();

        if (open.size() > 0) {
            first.add(new String[]{today.minusDays(3).toString(), amount(open.get(0)), open.get(0).getReference() + " medlemsavgift", payers.get(0).name()});
        }
        if (open.size() > 1) {
            // a reference with a wrong check character: found as a suggestion
            final String reference = open.get(1).getReference();
            final char last = reference.charAt(reference.length() - 1);
            final char other = PaymentReference.ALPHABET.charAt((PaymentReference.ALPHABET.indexOf(last) + 1) % PaymentReference.ALPHABET.length());

            second.add(new String[]{payers.get(1).name(), today.minusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    amount(open.get(1)), reference.substring(0, reference.length() - 1) + other + " avgift"});
        }
        if (open.size() > 2) {
            // no reference: by name and amount
            first.add(new String[]{today.minusDays(2).toString(), amount(open.get(2)), "Medlemsavgift", payers.get(2).name()});
        }
        if (open.size() > 4) {
            // two members in one payment
            first.add(new String[]{today.minusDays(1).toString(), "%d,00".formatted(open.get(3).getAmount() + open.get(4).getAmount()),
                    open.get(3).getReference() + " " + open.get(4).getReference(), payers.get(3).name()});
        }
        if (open.size() > 5) {
            // too small
            second.add(new String[]{payers.get(5).name(), today.minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    "%d.00".formatted(open.get(5).getAmount() - 50), open.get(5).getReference()});
        }
        first.add(new String[]{today.toString(), "800,00", "Hyra lokal " + year, "Någon Annan AB"});
        second.add(new String[]{"Någon Annan AB", today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), "55.00", "Ränta"});
        return List.of(new Statement(semicolon, first), new Statement(comma, second));
    }

    private static String amount(final PaymentRequest request) {
        return "%d,00".formatted(request.getAmount());
    }

    private void writeStatements(final List<Statement> statements) {
        try {
            Files.createDirectories(statementDir);
            for (final Statement statement : statements) {
                final BankFormat format = statement.format();
                final char separator = format.getDelimiter().charAt(0);
                final boolean semicolon = separator == ';';
                final StringBuilder out = new StringBuilder(semicolon ? "Datum;Belopp;Text;Avsändare\n" : "Avsändare,Datum,Belopp,Text\n");

                for (final String[] row : statement.rows()) {
                    out.append(String.join(String.valueOf(separator), row)).append('\n');
                }

                final Path file = statementDir.resolve(semicolon ? "sample-statement-semicolon.csv" : "sample-statement-comma.csv");

                Files.writeString(file, out, Charset.forName(format.getCharset()));
                log.info("Wrote the sample bank statement {} for the format \"{}\"", file.toAbsolutePath(), format.getName());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The one thing done in Keycloak: accounts waiting for approval, one that has verified its address and one that has
     * not, made when missing. A Keycloak that cannot be reached only means the registration cases are left out.
     */
    private void pendingAccounts() {
        try {
            pending("pending.verified@fgv.nu", "Vera", "Verifierad", true, "Jag var med i spexet 2005.");
            pending("pending.unverified@fgv.nu", "Olle", "Overifierad", false, null);
        } catch (final RuntimeException e) {
            log.warn("Could not create the sample accounts waiting for approval in Keycloak: {}", e.getMessage());
        }
    }

    private void pending(final String email, final String firstName, final String lastName, final boolean verified, final String message) {
        final Optional<KeycloakUser> existing = keycloak.findUserByEmail(email);
        final String id = existing.map(KeycloakUser::id).orElseGet(() -> keycloak.createUser(email, firstName, lastName, "sv"));
        final String group = properties.keycloak().pendingGroup();

        if (!keycloak.isMemberOfGroup(id, group)) {
            keycloak.joinGroup(id, group);
        }
        keycloak.setEmailVerified(id, verified);

        final Registration registration = new Registration(id, hash("sample-registration-" + email), clock.instant().plus(properties.registration().tokenValidity()), message);

        if (verified) {
            registration.markVerified();
        }
        transaction.executeWithoutResult(status -> registrations.save(registration));
    }

    private static Instant endOfNextYear(final int year) {
        return Year.of(year + 1).atDay(1).atStartOfDay(ZONE).toInstant();
    }

    private static String hash(final String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
