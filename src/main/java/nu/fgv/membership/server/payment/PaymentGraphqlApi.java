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
import nu.fgv.membership.server.membership.MembershipRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Paying for memberships (members) and recording what arrived (the treasurer), and fee levels (admins).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class PaymentGraphqlApi {

    private static final String USER = "hasRole('fgv-membership_USER')";
    private static final String TREASURER = "hasRole('fgv-membership_TREASURER')";
    private static final String ADMIN = "hasRole('fgv-membership_ADMIN')";

    private final PaymentService payments;
    private final FeeService fees;
    private final MembershipRepository memberships;
    private final SwishLinks swish;
    private final nu.fgv.membership.server.board.AuditService audit;

    public PaymentGraphqlApi(final PaymentService payments, final FeeService fees, final MembershipRepository memberships,
                             final SwishLinks swish, final nu.fgv.membership.server.board.AuditService audit) {
        this.audit = audit;
        this.payments = payments;
        this.fees = fees;
        this.memberships = memberships;
        this.swish = swish;
    }

    public record MyMembership(boolean linked, List<MembershipItem> memberships, List<PaymentView> requests) {
    }

    public record MembershipItem(String id, String type, int year, boolean lifetime, String status) {
    }

    public record FeeLevelView(String id, Integer year, FeeKind kind, boolean lifetime, String nameSv, String nameEn,
                               int amount, boolean retired) {
    }

    public record FeeLevelInput(String id, Integer year, FeeKind kind, boolean lifetime, String nameSv, String nameEn,
                                int amount) {
    }

    /**
     * The caller's memberships and payment requests; {@code linked} is false when their account has no spexare.
     */
    @QueryMapping
    @PreAuthorize(USER)
    public MyMembership myMembership(@AuthenticationPrincipal final Jwt caller) {
        final Spexare spexare;

        try {
            spexare = payments.spexareOf(caller.getSubject());
        } catch (final PaymentService.NoSpexareException e) {
            return new MyMembership(false, List.of(), List.of());
        }

        final List<MembershipItem> items = memberships.findBySpexareId(spexare.id()).stream()
                .sorted(Comparator.comparingInt(Membership::getYear).reversed())
                .map(m -> new MembershipItem(m.getId(), m.getType(), m.getYear(), m.isLifetime(), m.getStatus().name())).toList();

        return new MyMembership(true, items, payments.requestsOf(caller.getSubject()));
    }

    @QueryMapping
    @PreAuthorize(USER)
    public List<FeeLevelView> feeLevels(@Argument final Integer year) {
        return fees.available(year).stream().map(PaymentGraphqlApi::view).toList();
    }

    @QueryMapping
    @PreAuthorize(USER)
    public PaymentView paymentRequest(@AuthenticationPrincipal final Jwt caller, final Authentication authentication, @Argument final String id) {
        return payments.get(caller.getSubject(), id, hasTreasurer(authentication));
    }

    @MutationMapping
    @PreAuthorize(USER)
    public PaymentView requestPayment(@AuthenticationPrincipal final Jwt caller, @Argument final List<String> feeLevelIds, @Argument final String familyNote) {
        return payments.request(payments.spexareOf(caller.getSubject()).id(), caller.getSubject(), feeLevelIds, familyNote, List.of());
    }

    @MutationMapping
    @PreAuthorize(USER)
    public PaymentView reportPaid(@AuthenticationPrincipal final Jwt caller, @Argument final String id, @Argument final String paidBy) {
        return payments.report(caller.getSubject(), id, paidBy);
    }

    @MutationMapping
    @PreAuthorize("hasRole('fgv-membership_BOARD')")
    public PaymentView addFamilyMembers(@AuthenticationPrincipal final Jwt caller, @Argument final String requestId, @Argument final List<String> spexareIds) {
        final PaymentView view = payments.addFamilyMembers(requestId, spexareIds);

        audit.log(caller.getSubject(), "FAMILY_MEMBERS_ADDED", view.reference(), spexareIds.size() + " added");
        return view;
    }

    /**
     * Who a family request is for, named from Spexregister; only the board sees the names.
     */
    @SchemaMapping(typeName = "PaymentRequest")
    @PreAuthorize("hasRole('fgv-membership_BOARD')")
    public List<Spexare> familyMembers(final PaymentView request) {
        return payments.peopleOf(request);
    }

    /**
     * The payment link and QR code are made when asked for, so that lists do not call Swish.
     */
    @SchemaMapping(typeName = "PaymentRequest")
    public String swishLink(final PaymentView request) {
        return request.status() == PaymentStatus.OPEN || request.status() == PaymentStatus.REPORTED
                ? swish.link(request.amount(), request.reference()) : null;
    }

    @SchemaMapping(typeName = "PaymentRequest")
    public String qr(final PaymentView request) {
        return request.status() == PaymentStatus.OPEN || request.status() == PaymentStatus.REPORTED
                ? swish.qr(request.amount(), request.reference()) : null;
    }

    // Treasurer

    @QueryMapping
    @PreAuthorize(TREASURER)
    public List<PaymentView> waitingPaymentRequests() {
        return payments.waiting();
    }

    @MutationMapping
    @PreAuthorize(TREASURER)
    public PaymentView recordPayment(@AuthenticationPrincipal final Jwt caller, @Argument final String requestId,
                                     @Argument final String paidOn, @Argument final int amount, @Argument final PaymentHow how,
                                     @Argument final String note) {
        final PaymentView view = payments.recordPayment(caller.getSubject(), requestId, LocalDate.parse(paidOn), amount, how,
                note == null || note.isBlank() ? null : note.strip());

        audit.log(caller.getSubject(), "PAYMENT_RECORDED", view.reference(), "%d kr %s".formatted(amount, how));
        return view;
    }

    @MutationMapping
    @PreAuthorize(TREASURER)
    public PaymentView cancelPaymentRequest(@AuthenticationPrincipal final Jwt caller, @Argument final String id) {
        final PaymentView view = payments.cancel(id);

        audit.log(caller.getSubject(), "REQUEST_CANCELLED", view.reference(), null);
        return view;
    }

    // Admin

    @QueryMapping
    @PreAuthorize(ADMIN)
    public List<FeeLevelView> allFeeLevels() {
        return fees.all().stream().map(PaymentGraphqlApi::view).toList();
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public FeeLevelView feeLevelSave(@AuthenticationPrincipal final Jwt caller, @Argument final FeeLevelInput input) {
        final FeeLevelView saved = view(fees.save(input.id(), input.year(), input.kind() != null ? input.kind() : input.lifetime() ? FeeKind.LIFETIME : FeeKind.STANDARD, input.nameSv(), input.nameEn(), input.amount()));

        audit.log(caller.getSubject(), input.id() == null ? "FEE_ADDED" : "FEE_CHANGED", saved.id(), "%s %s: %d kr".formatted(
                saved.kind() + " " + (saved.year() == null ? "" : saved.year()), saved.nameEn(), saved.amount()));
        return saved;
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public FeeLevelView feeLevelRetire(@AuthenticationPrincipal final Jwt caller, @Argument final String id, @Argument final boolean retired) {
        audit.log(caller.getSubject(), retired ? "FEE_RETIRED" : "FEE_RESTORED", id, null);
        return view(fees.retire(id, retired));
    }

    private static boolean hasTreasurer(final Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_fgv-membership_TREASURER"));
    }

    private static FeeLevelView view(final FeeLevel level) {
        return new FeeLevelView(level.getId(), level.getYear(), level.getKind(), level.isLifetime(), level.getNameSv(), level.getNameEn(), level.getAmount(),
                level.isRetired());
    }
}
