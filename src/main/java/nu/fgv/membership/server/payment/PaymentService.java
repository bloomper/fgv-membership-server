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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Paying for memberships: a request with a reference is made, the payer says they paid, and the treasurer records the
 * payment, which makes the memberships count. Nothing is ever told by Swish, so recording is a person's decision.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class PaymentService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    private static final int MAX_REFERENCE_TRIES = 20;

    private final PaymentRequestRepository requests;
    private final PaymentRepository payments;
    private final FeeLevelRepository fees;
    private final MembershipService memberships;
    private final MembershipRepository membershipRepository;
    private final SpexregisterClient spexregister;
    private final PaymentMails mails;
    private final PaymentSettings properties;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public PaymentService(final PaymentRequestRepository requests, final PaymentRepository payments, final FeeLevelRepository fees,
                          final MembershipService memberships, final MembershipRepository membershipRepository,
                          final SpexregisterClient spexregister, final PaymentMails mails, final PaymentSettings properties,
                          final Clock clock) {
        this.requests = requests;
        this.payments = payments;
        this.fees = fees;
        this.memberships = memberships;
        this.membershipRepository = membershipRepository;
        this.spexregister = spexregister;
        this.mails = mails;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The spexare the user is linked to in Spexregister.
     *
     * @throws NoSpexareException when there is none; the user then applies for membership (spec 5.6)
     */
    public Spexare spexareOf(final String userId) {
        return spexregister.forUser(userId).orElseThrow(NoSpexareException::new);
    }

    public static class NoSpexareException extends RuntimeException {
        public NoSpexareException() {
            super("Your account is not linked to a spexare");
        }
    }

    /**
     * Asks to pay the fee levels, each for a year or for life. The memberships are created as {@code PENDING}.
     */
    @Transactional
    public PaymentView request(final String userId, final List<String> feeLevelIds) {
        return request(spexareOf(userId).id(), userId, feeLevelIds);
    }

    static final int MAX_FAMILY = 10;

    /**
     * @param spexareId whose memberships these are
     * @param createdBy who asks: the user, or later the board
     */
    @Transactional
    public PaymentView request(final String spexareId, final String createdBy, final List<String> feeLevelIds) {
        return request(spexareId, createdBy, feeLevelIds, null, List.of());
    }

    /**
     * Asks to pay fee levels, each for a year or for life. With a family fee one request pays for the whole family: the payer
     * is a member and so is everyone in {@code familyMemberIds}, who share a family id. The payer may say who the others
     * are in {@code familyNote} (free text, since names stay in Spexregister) for the board to attach them, which can also
     * be done later with {@link #addFamilyMembers}.
     *
     * @param familyNote      who the payer says the family members are; only kept for a family fee
     * @param familyMemberIds the other family members, by spexare, when they are known
     */
    @Transactional
    public PaymentView request(final String spexareId, final String createdBy, final List<String> feeLevelIds, final String familyNote,
                               final List<String> familyMemberIds) {
        if (feeLevelIds == null || feeLevelIds.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one fee");
        }

        final int thisYear = Year.now(clock.withZone(ZONE)).getValue();
        final Set<String> membershipIds = new LinkedHashSet<>();
        final Set<Integer> years = new LinkedHashSet<>();
        final List<String> others = familyMemberIds == null ? List.of() : familyMemberIds.stream().filter(id -> !id.equals(spexareId)).distinct().toList();
        final String familyId = UUID.randomUUID().toString();
        int total = 0;
        boolean lifetimeChosen = false;
        boolean family = false;

        for (final String id : new LinkedHashSet<>(feeLevelIds)) {
            final FeeLevel level = fees.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown fee " + id));

            if (level.isRetired()) {
                throw new IllegalArgumentException("That fee is no longer offered");
            }

            final int year = level.isLifetime() ? thisYear : level.getYear();

            if (level.isLifetime() ? lifetimeChosen : !years.add(year)) {
                throw new IllegalArgumentException("Pick one fee for each year");
            }
            lifetimeChosen |= level.isLifetime();
            refuseIfMember(spexareId, level, year);
            if (level.isFamily()) {
                family = true;
                if (others.size() + 1 > MAX_FAMILY) {
                    throw new IllegalArgumentException("A family can have at most %d members".formatted(MAX_FAMILY));
                }
                others.forEach(other -> refuseIfMember(other, level, year));
            }

            final Membership payer = memberships.record(spexareId, level.getType(), year, level.isLifetime(), MembershipStatus.PENDING, null, null);

            membershipIds.add(payer.getId());
            payer.setSupport(level.isSupport());
            if (level.isFamily()) {
                payer.setFamilyId(familyId);
                for (final String other : others) {
                    final Membership member = memberships.record(other, level.getType(), year, false, MembershipStatus.PENDING, null, null);

                    member.setFamilyId(familyId);
                    membershipIds.add(member.getId());
                }
            }
            total += level.getAmount();
        }

        final PaymentRequest request = new PaymentRequest(newReference(thisYear), spexareId, total, createdBy, membershipIds, clock.instant());

        if (family) {
            request.family(familyId, familyNote == null || familyNote.isBlank() ? null : familyNote.strip().substring(0, Math.min(500, familyNote.strip().length())));
        }
        return view(requests.save(request), null);
    }

    private void refuseIfMember(final String spexareId, final FeeLevel level, final int year) {
        if (membershipRepository.findBySpexareId(spexareId).stream().anyMatch(existing -> existing.getType().equals(level.getType())
                && existing.counts() && (existing.isLifetime() && existing.getYear() <= year || existing.getYear() == year))) {
            throw new IllegalArgumentException("Already a member for " + year);
        }
    }

    /**
     * Attaches family members to a family request, by the board: they get the membership too, in the state the payer's is
     * in (so paid when the request is paid). Members who already are members that year are refused.
     */
    @Transactional
    public PaymentView addFamilyMembers(final String requestId, final List<String> spexareIds) {
        final PaymentRequest request = find(requestId);

        if (request.getFamilyId() == null) {
            throw new IllegalStateException("That is not a family request");
        }
        if (request.getStatus() == PaymentStatus.CANCELLED) {
            throw new IllegalStateException("The request is cancelled");
        }

        final List<Membership> payers = request.getMembershipIds().stream().map(id -> membershipRepository.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull).filter(m -> m.getSpexareId().equals(request.getSpexareId())).toList();
        final List<String> wanted = spexareIds.stream().filter(id -> !id.equals(request.getSpexareId())).distinct().toList();
        final long people = request.getMembershipIds().stream().map(id -> membershipRepository.findById(id).orElse(null)).filter(java.util.Objects::nonNull)
                .map(Membership::getSpexareId).distinct().count();

        if (people + wanted.stream().filter(id -> request.getMembershipIds().stream().map(m -> membershipRepository.findById(m).orElse(null))
                .filter(java.util.Objects::nonNull).noneMatch(m -> m.getSpexareId().equals(id))).count() > MAX_FAMILY) {
            throw new IllegalArgumentException("A family can have at most %d members".formatted(MAX_FAMILY));
        }
        for (final Membership payer : payers) {
            for (final String id : wanted) {
                if (request.getMembershipIds().stream().map(m -> membershipRepository.findById(m).orElse(null)).filter(java.util.Objects::nonNull)
                        .anyMatch(m -> m.getSpexareId().equals(id) && m.getYear() == payer.getYear())) {
                    continue;
                }
                if (membershipRepository.findBySpexareId(id).stream().anyMatch(existing -> existing.getType().equals(payer.getType()) && existing.counts()
                        && (existing.isLifetime() && existing.getYear() <= payer.getYear() || existing.getYear() == payer.getYear()))) {
                    throw new IllegalArgumentException("Already a member for " + payer.getYear());
                }

                final Membership member = memberships.record(id, payer.getType(), payer.getYear(), false, payer.getStatus(), payer.getHow(), payer.getNote());

                member.setFamilyId(request.getFamilyId());
                request.getMembershipIds().add(member.getId());
            }
        }
        return view(request, null);
    }

    /**
     * A payment made elsewhere (the WordPress shop): the memberships count at once, and what was asked and paid is kept
     * as a matched request, so that it is bookkeeping like any other.
     *
     * @param actor the system that told us, by Keycloak subject
     * @return the request, or empty when the spexare is already a member that year and nothing was recorded
     */
    @Transactional
    public java.util.Optional<PaymentView> recordExternal(final String actor, final String spexareId, final int year, final boolean lifetime,
                                                         final int amount, final LocalDate paidOn, final String note) {
        return recordExternal(actor, spexareId, year, lifetime, false, amount, paidOn, note);
    }

    /**
     * @param support a support membership (stödmedlemskap)
     */
    @Transactional
    public java.util.Optional<PaymentView> recordExternal(final String actor, final String spexareId, final int year, final boolean lifetime,
                                                         final boolean support, final int amount, final LocalDate paidOn, final String note) {
        final String payer = spexregister.byIds(List.of(spexareId)).stream().findFirst().map(Spexare::name).orElse(spexareId);

        if (membershipRepository.findBySpexareId(spexareId).stream().anyMatch(existing -> existing.getType().equals(properties.membershipType())
                && existing.counts() && (existing.isLifetime() && existing.getYear() <= year || existing.getYear() == year))) {
            mails.adminNotice(PaymentMails.Event.ALREADY_MEMBER, payer, amount, note, String.valueOf(year), note);
            return java.util.Optional.empty();
        }

        final Membership membership = memberships.record(spexareId, properties.membershipType(), year, lifetime, MembershipStatus.PAID,
                MembershipHow.ONLINE, note);

        membership.setSupport(support);

        final PaymentRequest request = new PaymentRequest(newReference(year), spexareId, amount, actor, Set.of(membership.getId()), clock.instant());

        request.matched();
        requests.save(request);
        payments.save(new Payment(request.getPk(), paidOn, amount, PaymentHow.ONLINE, actor, note, clock.instant()));

        final PaymentView view = view(request, null);

        spexregister.byIds(List.of(spexareId)).forEach(spexare -> mails.receipt(spexare, view));
        mails.adminNotice(PaymentMails.Event.ONLINE, payer, amount, view.reference(), years(view), note);
        return java.util.Optional.of(view);
    }

    /**
     * The payer says they paid, perhaps someone else on their behalf.
     */
    @Transactional
    public PaymentView report(final String userId, final String requestId, final String paidBy) {
        final PaymentRequest request = find(requestId);

        if (!ownedBy(request, userId)) {
            throw new AccessDeniedException("Not your payment request");
        }
        if (!request.isPayable()) {
            throw new IllegalStateException("The request is " + request.getStatus());
        }
        request.report(paidBy == null || paidBy.isBlank() ? null : paidBy.strip(), clock.instant());
        request.getMembershipIds().forEach(id -> memberships.setStatus(id, MembershipStatus.REPORTED, null, null));

        final PaymentView view = view(request, null);

        mails.adminNotice(PaymentMails.Event.REPORTED, spexareOf(userId).name(), view.amount(), view.reference(), years(view),
                request.getPaidBy() == null ? null : "Betalat av " + request.getPaidBy());
        return view;
    }

    /**
     * The treasurer records that money arrived for a request. When what has been paid covers the request, the memberships
     * become {@code PAID} and the payer gets a receipt; less leaves it open with the difference shown. More than asked is
     * kept as a gift: the payment is the amount that came.
     */
    @Transactional
    public PaymentView recordPayment(final String actor, final String requestId, final LocalDate paidOn, final int amount,
                                     final PaymentHow how, final String note) {
        return recordPayment(actor, requestId, paidOn, amount, how, note, null);
    }

    /**
     * @param transactionPk the bank transaction the money came in, if it did
     */
    @Transactional
    public PaymentView recordPayment(final String actor, final String requestId, final LocalDate paidOn, final int amount,
                                     final PaymentHow how, final String note, final Long transactionPk) {
        final PaymentRequest request = find(requestId);

        if (!request.isPayable()) {
            throw new IllegalStateException("The request is " + request.getStatus());
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("The amount must be more than nothing");
        }
        payments.save(new Payment(request.getPk(), paidOn, amount, how, actor, note, clock.instant()).fromTransaction(transactionPk));

        final boolean covered = paidSoFar(request) >= request.getAmount();

        if (covered) {
            request.matched();
            request.getMembershipIds().forEach(id -> memberships.setStatus(id, MembershipStatus.PAID,
                    request.isForRevival() ? MembershipHow.REVIVAL : membershipHow(how), note));
        }

        final PaymentView view = view(request, null);

        if (covered && request.getSpexareId() != null) {
            spexregister.byIds(List.of(request.getSpexareId())).forEach(spexare -> mails.receipt(spexare, view));
        }
        return view;
    }

    /**
     * A bank transaction that turns out to be a payment already recorded by hand is linked to it, not paid twice.
     */
    @Transactional
    public PaymentView linkTransaction(final String requestId, final Long transactionPk) {
        final PaymentRequest request = find(requestId);
        final Payment payment = payments.findByRequestPk(request.getPk()).stream().filter(p -> p.getTransactionPk() == null).findFirst()
                .orElseThrow(() -> new IllegalStateException("There is no payment without a transaction on " + request.getReference()));

        payment.linkTransaction(transactionPk);
        return view(request, null);
    }

    /**
     * Takes a recorded payment back, until the end of the year it was recorded. The request is open or reported again and
     * its memberships do not count.
     */
    @Transactional
    public PaymentView undoPayment(final String paymentId) {
        final Payment payment = payments.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("Unknown payment " + paymentId));

        if (payment.getRecordedAt().atZone(ZONE).getYear() != clock.instant().atZone(ZONE).getYear()) {
            throw new IllegalStateException("A payment can only be undone in the year it was recorded");
        }

        final PaymentRequest request = requests.findById(payment.getRequestPk()).orElseThrow();
        final boolean wasCovered = request.getStatus() == PaymentStatus.MATCHED;

        payments.delete(payment);
        if (wasCovered && paidSoFar(request) < request.getAmount()) {
            request.reopen();
            request.getMembershipIds().forEach(id -> memberships.setStatus(id, request.getStatus() == PaymentStatus.REPORTED
                    ? MembershipStatus.REPORTED : MembershipStatus.PENDING, null, null));
        }
        return view(request, null);
    }

    /**
     * @param reference the request's reference
     * @param spexareName who it was for
     * @param transaction whether it came from an imported bank transaction
     */
    public record PaymentLine(String id, String reference, String spexareName, java.time.LocalDate paidOn, int amount, PaymentHow how,
                              String note, boolean transaction) {
    }

    /**
     * The payments recorded this year, newest first: the ones that can still be undone.
     */
    @Transactional(readOnly = true)
    public List<PaymentLine> thisYearsPayments() {
        final Instant start = Year.now(clock.withZone(ZONE)).atDay(1).atStartOfDay(ZONE).toInstant();
        final List<Payment> found = payments.findByRecordedAtGreaterThanEqualOrderByRecordedAtDesc(start);
        final Map<Long, PaymentRequest> byPk = new java.util.HashMap<>();

        found.forEach(payment -> byPk.computeIfAbsent(payment.getRequestPk(), pk -> requests.findById(pk).orElse(null)));

        final Map<String, Spexare> spexare = spexregister.byIds(byPk.values().stream().filter(java.util.Objects::nonNull)
                .map(PaymentRequest::getSpexareId).filter(java.util.Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(Spexare::id, Function.identity()));

        return found.stream().filter(payment -> byPk.get(payment.getRequestPk()) != null).map(payment -> {
            final PaymentRequest request = byPk.get(payment.getRequestPk());

            return new PaymentLine(payment.getId(), request.getReference(), request.isForRevival() ? request.getPayerLabel()
                    : spexare.containsKey(request.getSpexareId()) ? spexare.get(request.getSpexareId()).name() : null, payment.getPaidOn(), payment.getAmount(), payment.getHow(),
                    payment.getNote(), payment.getTransactionPk() != null);
        }).toList();
    }

    /**
     * The payer says they paid, found by the request instead of by their account (a personal link).
     */
    @Transactional
    public PaymentView reportBy(final String requestId, final String paidBy) {
        final PaymentRequest request = find(requestId);

        if (!request.isPayable()) {
            throw new IllegalStateException("The request is " + request.getStatus());
        }
        request.report(paidBy == null || paidBy.isBlank() ? null : paidBy.strip(), clock.instant());
        request.getMembershipIds().forEach(id -> memberships.setStatus(id, MembershipStatus.REPORTED, null, null));

        final PaymentView view = view(request, null);
        if (request.getSpexareId() == null) {
            throw new IllegalStateException("A revival's request is paid by the revival, not reported");
        }

        final String payer = spexregister.byIds(List.of(request.getSpexareId())).stream().findFirst().map(Spexare::name).orElse(request.getSpexareId());

        mails.adminNotice(PaymentMails.Event.REPORTED, payer, view.amount(), view.reference(), years(view),
                request.getPaidBy() == null ? null : "Betalat av " + request.getPaidBy());
        return view;
    }

    /**
     * @return the years the request's memberships are for
     */
    public List<Integer> yearsOf(final PaymentRequest request) {
        return view(request, null).memberships().stream().map(PaymentView.Item::year).toList();
    }

    /**
     * @return the people a request's memberships are for, by name
     */
    @Transactional(readOnly = true)
    public List<Spexare> peopleOf(final PaymentView request) {
        return spexregister.byIds(request.memberships().stream().map(PaymentView.Item::spexareId).filter(java.util.Objects::nonNull).distinct().toList());
    }

    /**
     * @return what a request still lacks, in whole kronor
     */
    public int remaining(final PaymentRequest request) {
        return Math.max(0, request.getAmount() - paidSoFar(request));
    }

    private int paidSoFar(final PaymentRequest request) {
        return payments.findByRequestPk(request.getPk()).stream().mapToInt(Payment::getAmount).sum();
    }

    /**
     * The request is dropped; its memberships are cancelled unless they count.
     */
    @Transactional
    public PaymentView cancel(final String requestId) {
        final PaymentRequest request = find(requestId);

        if (!request.isPayable()) {
            throw new IllegalStateException("The request is " + request.getStatus());
        }
        request.cancel();
        request.getMembershipIds().forEach(id -> memberships.setStatus(id, MembershipStatus.CANCELLED, null, "Request cancelled"));
        return view(request, null);
    }

    /**
     * Reminds the payers of requests that nothing has happened to: sent once, when the request is older than the
     * reminder time and neither reported nor matched.
     *
     * @return how many were reminded
     */
    @Transactional
    public int remind() {
        final var due = requests.findByStatusAndReminderSentAtIsNullAndCreatedAtBefore(PaymentStatus.OPEN,
                clock.instant().minus(properties.reminderAfter())).stream().filter(request -> request.getSpexareId() != null).toList();

        if (due.isEmpty()) {
            return 0;
        }

        final Map<String, Spexare> spexare = spexregister.byIds(due.stream().map(PaymentRequest::getSpexareId).distinct().toList())
                .stream().collect(Collectors.toMap(Spexare::id, Function.identity()));

        due.forEach(request -> {
            request.reminded(clock.instant());
            if (spexare.containsKey(request.getSpexareId())) {
                mails.reminder(spexare.get(request.getSpexareId()), view(request, null));
            }
        });
        return due.size();
    }

    @Transactional(readOnly = true)
    public List<PaymentView> requestsOf(final String userId) {
        return requests.findBySpexareIdOrderByCreatedAtDesc(spexareOf(userId).id()).stream().map(request -> view(request, null)).toList();
    }

    /**
     * @return every payment request of a spexare, newest first, for the board's page about a member
     */
    @Transactional(readOnly = true)
    public List<PaymentView> requestsOfSpexare(final String spexareId) {
        return requests.findBySpexareIdOrderByCreatedAtDesc(spexareId).stream().map(request -> view(request, null)).toList();
    }

    /**
     * @return the request, for code that needs its key
     */
    @Transactional(readOnly = true)
    public PaymentRequest entity(final String requestId) {
        return find(requestId);
    }

    /**
     * @param userId the caller's account; the request must be theirs unless they are the treasurer
     */
    @Transactional(readOnly = true)
    public PaymentView get(final String userId, final String requestId, final boolean treasurer) {
        final PaymentRequest request = find(requestId);

        if (!treasurer && !ownedBy(request, userId)) {
            throw new AccessDeniedException("Not your payment request");
        }
        return view(request, treasurer && request.getSpexareId() != null
                ? spexregister.byIds(List.of(request.getSpexareId())).stream().findFirst().orElse(null) : null);
    }

    /**
     * The requests that wait for the treasurer: not yet paid, oldest first, with who they are for.
     */
    @Transactional(readOnly = true)
    public List<PaymentView> waiting() {
        final var open = requests.findByStatusInOrderByCreatedAtAsc(List.of(PaymentStatus.OPEN, PaymentStatus.REPORTED));
        final Map<String, Spexare> spexare = spexregister.byIds(open.stream().map(PaymentRequest::getSpexareId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Spexare::id, Function.identity()));

        return open.stream().map(request -> view(request, spexare.get(request.getSpexareId()))).toList();
    }

    private static String years(final PaymentView view) {
        return view.memberships().stream().map(item -> item.lifetime() ? "livstid från " + item.year() : String.valueOf(item.year()))
                .collect(Collectors.joining(", "));
    }

    private boolean ownedBy(final PaymentRequest request, final String userId) {
        return request.getSpexareId() != null && request.getSpexareId().equals(spexareOf(userId).id());
    }

    /**
     * @param request       the one request that pays for them all
     * @param included      how many memberships it covers
     * @param alreadyMembers the spexare who were members for the year already and are left out
     */
    public record RevivalRequest(PaymentView request, int included, List<String> alreadyMembers) {
    }

    /**
     * A revival pays the year's fee for its participants (spec 5.7): one request for the lot, which the treasurer matches
     * as usual, and the memberships become {@code PAID} with the revival as the payer. Participants who are members
     * for the year already are left out and reported.
     *
     * @param payerLabel who pays, e.g. the revival's name and year
     */
    @Transactional
    public RevivalRequest requestForRevival(final String actor, final String payerLabel, final List<String> spexareIds, final String feeLevelId) {
        if (payerLabel == null || payerLabel.isBlank()) {
            throw new IllegalArgumentException("Say who pays");
        }

        final FeeLevel level = fees.findById(feeLevelId).orElseThrow(() -> new IllegalArgumentException("Unknown fee"));

        if (level.getKind() != FeeKind.STANDARD || level.getYear() == null || level.isRetired()) {
            throw new IllegalArgumentException("Pick a standard yearly fee");
        }

        final int year = level.getYear();
        final Set<String> membershipIds = new LinkedHashSet<>();
        final List<String> already = new java.util.ArrayList<>();

        for (final String spexareId : new LinkedHashSet<>(spexareIds)) {
            if (membershipRepository.findBySpexareId(spexareId).stream().anyMatch(existing -> existing.getType().equals(level.getType())
                    && existing.counts() && (existing.isLifetime() && existing.getYear() <= year || existing.getYear() == year))) {
                already.add(spexareId);
            } else {
                membershipIds.add(memberships.record(spexareId, level.getType(), year, false, MembershipStatus.PENDING, null, null).getId());
            }
        }
        if (membershipIds.isEmpty()) {
            throw new IllegalArgumentException("Everyone is a member for " + year + " already");
        }

        final PaymentRequest request = requests.save(PaymentRequest.forRevival(newReference(Year.now(clock.withZone(ZONE)).getValue()), payerLabel.strip(),
                level.getAmount() * membershipIds.size(), actor, membershipIds, clock.instant()));

        return new RevivalRequest(view(request, null), membershipIds.size(), already);
    }

    private PaymentRequest find(final String id) {
        return requests.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown payment request " + id));
    }

    private String newReference(final int year) {
        for (int i = 0; i < MAX_REFERENCE_TRIES; i++) {
            final String reference = PaymentReference.generate(Year.of(year));

            if (!requests.existsByReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not make a free reference");
    }

    private static MembershipHow membershipHow(final PaymentHow how) {
        return switch (how) {
            case SWISH -> MembershipHow.SWISH;
            case BANK_TRANSFER -> MembershipHow.BANK_TRANSFER;
            case CASH -> MembershipHow.CASH;
            case ONLINE -> MembershipHow.ONLINE;
        };
    }

    private PaymentView view(final PaymentRequest request, final Spexare spexare) {
        final List<PaymentView.Item> items = request.getMembershipIds().stream()
                .map(id -> membershipRepository.findById(id).orElse(null)).filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(Membership::getYear))
                .map(m -> new PaymentView.Item(m.getId(), m.getYear(), m.isLifetime(), m.getStatus().name(), m.getSpexareId(), m.getFamilyId() != null)).toList();

        return new PaymentView(request.getId(), request.getReference(), request.getAmount(), request.getStatus(), request.getCreatedAt(),
                request.getReportedAt(), request.getPaidBy(), request.getSpexareId(),
                spexare != null ? spexare.name() : request.getPayerLabel(), items,
                new PaymentView.Payee(properties.swishNumber(), properties.bankgiro(), properties.bankAccount(), properties.payeeName()), null, paidSoFar(request),
                request.getFamilyNote(), request.getFamilyId() != null,
                request.getFamilyId() != null && items.stream().map(PaymentView.Item::spexareId).distinct().count() < 2);
    }
}
