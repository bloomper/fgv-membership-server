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

import nu.fgv.membership.server.payment.FeeLevel;
import nu.fgv.membership.server.payment.FeeLevelRepository;
import nu.fgv.membership.server.payment.PaymentMails;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.payment.PaymentView;
import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renewal reminders: the board picks a year and a fee, sees who would get the email, and sends it. Each member gets a
 * payment request and a personal link. Those who chose <em>don't remind me again</em>, life members, and those who
 * have a request or a membership for the year already are left out.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class RenewalService {

    private static final Logger log = LoggerFactory.getLogger(RenewalService.class);

    private final JdbcClient jdbc;
    private final FeeLevelRepository fees;
    private final PaymentService payments;
    private final PersonalLinkService links;
    private final SpexregisterClient spexregister;
    private final PaymentMails mails;
    private final AuditService audit;
    private final Clock clock;
    private final String webUrl;

    @SuppressWarnings("java:S107")
    public RenewalService(final JdbcClient jdbc, final FeeLevelRepository fees, final PaymentService payments, final PersonalLinkService links,
                          final SpexregisterClient spexregister, final PaymentMails mails, final AuditService audit, final Clock clock,
                          @Value("${membership.web-url}") final String webUrl) {
        this.jdbc = jdbc;
        this.fees = fees;
        this.payments = payments;
        this.links = links;
        this.spexregister = spexregister;
        this.mails = mails;
        this.audit = audit;
        this.clock = clock;
        this.webUrl = webUrl;
    }

    /**
     * @param count  how many would be reminded
     * @param sample the first few, by name
     */
    public record Preview(int year, int count, List<String> sample, String subject, String text) {
    }

    /**
     * @param sent    reminders sent
     * @param skipped members without an email address, or whose email could not be sent
     */
    public record Result(int sent, int skipped) {
    }

    /**
     * Last year's members who have nothing for the year, are not for life and did not opt out. Family and support memberships
     * are left out: they renew with their own fee, which the board asks for on the member's page.
     */
    public List<String> group(final int year) {
        return jdbc.sql("""
                        SELECT DISTINCT m.spexare_id FROM membership m
                        WHERE m.type = 'FGV' AND m.status IN ('PAID', 'WAIVED') AND m.membership_year = :last AND m.family_id IS NULL AND NOT m.support
                          AND NOT EXISTS (SELECT 1 FROM membership l WHERE l.spexare_id = m.spexare_id AND l.type = m.type AND l.lifetime
                                          AND l.status IN ('PAID', 'WAIVED') AND l.membership_year <= :last)
                          AND NOT EXISTS (SELECT 1 FROM membership n WHERE n.spexare_id = m.spexare_id AND n.type = m.type AND n.membership_year = :year
                                          AND n.status <> 'CANCELLED')
                          AND NOT EXISTS (SELECT 1 FROM reminder_opt_out o WHERE o.spexare_id = m.spexare_id)""")
                .param("last", year - 1).param("year", year).query(String.class).list();
    }

    public Preview preview(final int year, final String feeLevelId) {
        final FeeLevel fee = fee(year, feeLevelId);
        final List<String> ids = group(year);
        final List<String> sample = ids.isEmpty() ? List.of() : spexregister.byIds(ids.subList(0, Math.min(10, ids.size()))).stream().map(Spexare::name).toList();

        return new Preview(year, ids.size(), sample, "Förnya ditt medlemskap i FGV",
                "Dags att förnya medlemskapet i FGV för %d: %d kr, med en personlig länk för att betala. Mottagaren kan välja att inte få fler påminnelser. Familje- och stödmedlemskap påminns inte här: styrelsen ber dem att betala från medlemmens sida.".formatted(year, fee.getAmount()));
    }

    /**
     * Sends the reminders. Each member is handled on its own: one who fails does not stop the others.
     */
    public Result send(final String actor, final int year, final String feeLevelId) {
        final FeeLevel fee = fee(year, feeLevelId);
        final List<String> ids = group(year);
        final Map<String, Spexare> people = new HashMap<>();

        for (int i = 0; i < ids.size(); i += 500) {
            spexregister.byIds(ids.subList(i, Math.min(ids.size(), i + 500))).forEach(spexare -> people.put(spexare.id(), spexare));
        }

        final String campaign = UUID.randomUUID().toString();
        final List<String[]> sent = new ArrayList<>();
        int skipped = 0;

        for (final String spexareId : ids) {
            final Spexare spexare = people.get(spexareId);

            if (spexare == null || spexare.email() == null || spexare.email().isBlank()) {
                skipped++;
                continue;
            }
            try {
                final PaymentView request = payments.request(spexareId, actor, List.of(fee.getId()));
                final String token = links.create(payments.entity(request.id()));

                if (mails.paymentLink(spexare, request, webUrl + "/pay/" + token, true)) {
                    sent.add(new String[]{spexareId, request.id()});
                } else {
                    skipped++;
                }
            } catch (final RuntimeException e) {
                log.error("Could not remind spexare {}", spexareId, e);
                skipped++;
            }
        }

        jdbc.sql("INSERT INTO reminder_campaign (id, campaign_year, fee_level_id, sent_by, sent_at, recipients, skipped) VALUES (:id, :year, :fee, :by, :at, :n, :s)")
                .param("id", campaign).param("year", year).param("fee", fee.getId()).param("by", actor).param("at", Timestamp.from(clock.instant()))
                .param("n", sent.size()).param("s", skipped).update();

        final Long pk = jdbc.sql("SELECT pk FROM reminder_campaign WHERE id = :id").param("id", campaign).query(Long.class).single();

        sent.forEach(pair -> jdbc.sql("INSERT INTO reminder_email (campaign_pk, spexare_id, request_id, kind, sent_at) VALUES (:c, :s, :r, 'RENEWAL', :at)")
                .param("c", pk).param("s", pair[0]).param("r", pair[1]).param("at", Timestamp.from(clock.instant())).update());
        audit.log(actor, "RENEWAL_REMINDERS", String.valueOf(year), "%d sent, %d skipped".formatted(sent.size(), skipped));
        return new Result(sent.size(), skipped);
    }

    private FeeLevel fee(final int year, final String feeLevelId) {
        final FeeLevel fee = fees.findById(feeLevelId).orElseThrow(() -> new IllegalArgumentException("Unknown fee"));

        if (fee.getKind() != nu.fgv.membership.server.payment.FeeKind.STANDARD || fee.getYear() == null || fee.getYear() != year || fee.isRetired()) {
            throw new IllegalArgumentException("Pick a fee for " + year);
        }
        return fee;
    }

    static Map<String, Spexare> byId(final List<Spexare> spexare) {
        return spexare.stream().collect(Collectors.toMap(Spexare::id, Function.identity()));
    }
}
