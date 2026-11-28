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

import nu.fgv.membership.server.membership.Membership;
import nu.fgv.membership.server.membership.MembershipHow;
import nu.fgv.membership.server.membership.MembershipRepository;
import nu.fgv.membership.server.membership.MembershipService;
import nu.fgv.membership.server.membership.MembershipStatus;
import nu.fgv.membership.server.payment.PaymentHow;
import nu.fgv.membership.server.payment.PaymentMails;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentView;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.Comparator;
import java.util.List;

/**
 * What the board does with members one at a time: waive a fee, record a payment that has no request, send a payment
 * link, and read a member's page.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class BoardService {

    private final MembershipService memberships;
    private final MembershipRepository membershipRepository;
    private final PaymentService payments;
    private final PersonalLinkService links;
    private final SpexregisterClient spexregister;
    private final PaymentMails mails;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final OptOutRepository optOuts;
    private final String webUrl;

    @SuppressWarnings("java:S107")
    public BoardService(final MembershipService memberships, final MembershipRepository membershipRepository, final PaymentService payments,
                        final PersonalLinkService links, final SpexregisterClient spexregister, final PaymentMails mails, final AuditService audit,
                        final JdbcClient jdbc, final OptOutRepository optOuts, @Value("${membership.web-url}") final String webUrl) {
        this.memberships = memberships;
        this.membershipRepository = membershipRepository;
        this.payments = payments;
        this.links = links;
        this.spexregister = spexregister;
        this.mails = mails;
        this.audit = audit;
        this.jdbc = jdbc;
        this.optOuts = optOuts;
        this.webUrl = webUrl;
    }

    public record MemberItem(String id, String type, int year, boolean lifetime, String status, String how,
                             String note) {
    }

    public record EmailItem(String kind, String sentAt) {
    }

    public record Detail(Spexare spexare, boolean optedOut, List<MemberItem> memberships, List<PaymentView> requests,
                         List<EmailItem> emails) {
    }

    /**
     * Grants a membership without a fee, for a year or for life from it, with a note.
     */
    @Transactional
    public MemberItem waive(final String actor, final String spexareId, final int year, final boolean lifetime, final String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A waiver needs a note saying why");
        }
        if (membershipRepository.findBySpexareId(spexareId).stream().anyMatch(m -> m.getType().equals(Membership.FGV) && m.counts()
                && (m.getYear() == year || m.isLifetime() && m.getYear() <= year))) {
            throw new IllegalArgumentException("Already a member for " + year);
        }

        final Membership membership = memberships.record(spexareId, Membership.FGV, year, lifetime, MembershipStatus.WAIVED, MembershipHow.WAIVED, note.strip());

        audit.log(actor, "WAIVER", spexareId, "%s %d: %s".formatted(lifetime ? "life from" : "year", year, note.strip()));
        return item(membership);
    }

    /**
     * A payment that has no request yet, e.g. a member who paid without asking first: the request is made and paid in
     * one go.
     */
    @Transactional
    public PaymentView recordWithoutRequest(final String actor, final String spexareId, final List<String> feeLevelIds, final LocalDate paidOn,
                                            final int amount, final PaymentHow how, final String note) {
        final PaymentView request = payments.request(spexareId, actor, feeLevelIds);
        final PaymentView paid = payments.recordPayment(actor, request.id(), paidOn, amount, how, note);

        audit.log(actor, "PAYMENT_WITHOUT_REQUEST", spexareId, "%d kr %s, %s".formatted(amount, how, request.reference()));
        return paid;
    }

    /**
     * Asks a member to pay, by email with a personal link.
     *
     * @return whether the email could be sent; the request exists either way
     */
    @Transactional
    public boolean sendPaymentLink(final String actor, final String spexareId, final List<String> feeLevelIds) {
        final PaymentView request = payments.request(spexareId, actor, feeLevelIds);
        final String token = links.create(payments.entity(request.id()));
        final Spexare spexare = spexregister.byIds(List.of(spexareId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Spexregister does not know that spexare"));
        final boolean sent = mails.paymentLink(spexare, request, webUrl + "/pay/" + token);

        if (sent) {
            jdbc.sql("INSERT INTO reminder_email (campaign_pk, spexare_id, request_id, kind, sent_at) VALUES (NULL, :s, :r, 'LINK', NOW(6))")
                    .param("s", spexareId).param("r", request.id()).update();
        }
        audit.log(actor, "PAYMENT_LINK", spexareId, request.reference() + (sent ? "" : " (no email sent)"));
        return sent;
    }

    @Transactional(readOnly = true)
    public Detail detail(final String spexareId) {
        final Spexare spexare = spexregister.byIds(List.of(spexareId)).stream().findFirst().orElse(new Spexare(spexareId, "", "", null));

        return new Detail(spexare, optOuts.existsById(spexareId),
                membershipRepository.findBySpexareId(spexareId).stream().sorted(Comparator.comparingInt(Membership::getYear).reversed()).map(BoardService::item).toList(),
                payments.requestsOfSpexare(spexareId),
                jdbc.sql("SELECT kind, sent_at FROM reminder_email WHERE spexare_id = :s ORDER BY sent_at DESC").param("s", spexareId)
                        .query((rs, i) -> new EmailItem(rs.getString("kind"), rs.getTimestamp("sent_at").toInstant().toString())).list());
    }

    /**
     * @return who matches a search by name, for picking the spexare to waive or record for
     */
    public List<Spexare> search(final String query) {
        return query == null || query.strip().length() < 2 ? List.of() : spexregister.search(query.strip());
    }

    static MemberItem item(final Membership m) {
        return new MemberItem(m.getId(), m.getType(), m.getYear(), m.isLifetime(), m.getStatus().name(), m.getHow() == null ? null : m.getHow().name(), m.getNote());
    }

    int thisYear() {
        return Year.now().getValue();
    }
}
