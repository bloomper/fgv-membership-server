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

import nu.fgv.membership.server.mail.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The emails about payments: the reminder and the receipt. They go to the spexare's address in Spexregister, in Swedish,
 * which is what Spexregister knows the member by.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class PaymentMails {

    private static final Logger log = LoggerFactory.getLogger(PaymentMails.class);
    private static final Locale LOCALE = Locale.of("sv");

    private final Mailer mailer;
    private final PaymentProperties properties;
    private final String webUrl;
    private final java.util.List<String> adminRecipients;

    public PaymentMails(final Mailer mailer, final PaymentProperties properties,
                        @org.springframework.beans.factory.annotation.Value("${membership.web-url}") final String webUrl,
                        @org.springframework.beans.factory.annotation.Value("${membership.registration.notify-recipients:}") final java.util.List<String> adminRecipients) {
        this.mailer = mailer;
        this.properties = properties;
        this.webUrl = webUrl;
        this.adminRecipients = adminRecipients.stream().map(String::strip).filter(recipient -> !recipient.isEmpty()).toList();
    }

    /**
     * What happened that the administrators are told about.
     */
    public enum Event {
        /** A member says they have paid; the treasurer has to find the payment */
        REPORTED,
        /** The shop told that someone paid; the membership counts already */
        ONLINE,
        /** The shop told about a payment from someone who already was a member that year; a refund may be due */
        ALREADY_MEMBER
    }

    /**
     * Tells the administrators that a payment was made or reported, whether by "I have paid" or from the shop.
     *
     * @param payer     who paid, by name
     * @param reference the payment request's reference, or the shop's order when nothing was recorded
     * @param years     what it is for
     * @param note      who paid on their behalf, or the shop's order, or null
     */
    public void adminNotice(final Event event, final String payer, final int amount, final String reference, final String years, final String note) {
        final String key = event.name().toLowerCase(Locale.ROOT);
        final String extra = note == null || note.isBlank() ? "" : mailer.text(LOCALE, "mail.payment.admin.note", note);

        for (final String recipient : adminRecipients) {
            try {
                mailer.send(recipient, mailer.text(LOCALE, "mail.payment.admin." + key + ".subject", payer, amount),
                        mailer.text(LOCALE, "mail.payment.admin." + key + ".body", payer, amount, reference, years, extra, webUrl + "/treasurer/payments"));
            } catch (final MailException e) {
                log.error("Could not tell {} about a payment from {}", recipient, payer, e);
            }
        }
    }

    public void reminder(final Spexare spexare, final PaymentView request) {
        send(spexare, mailer.text(LOCALE, "mail.payment.reminder.subject", request.reference()),
                mailer.text(LOCALE, "mail.payment.reminder.body", request.amount(), request.reference(),
                        properties.swishNumber() == null ? "" : properties.swishNumber(),
                        properties.bankAccount() == null ? "" : properties.bankAccount(), webUrl + "/membership/pay/" + request.id()));
    }

    /**
     * Tells someone that an account has been made for them, because they paid online. A separate message from the login
     * service has the link to choose a password.
     */
    public void accountCreated(final String userId, final String email) {
        try {
            mailer.send(email, mailer.text(LOCALE, "mail.account.subject"), mailer.text(LOCALE, "mail.account.body", webUrl));
        } catch (final MailException e) {
            log.error("Could not tell {} that an account was created", userId, e);
        }
    }

    /**
     * A request to pay with a personal link: the renewal reminder, or a request the board made.
     */
    public boolean paymentLink(final Spexare spexare, final PaymentView request, final String link, final boolean renewal) {
        if (spexare.email() == null || spexare.email().isBlank()) {
            return false;
        }
        try {
            mailer.send(spexare.email(), mailer.text(LOCALE, renewal ? "mail.renewal.subject" : "mail.link.subject"),
                    mailer.text(LOCALE, renewal ? "mail.renewal.body" : "mail.link.body", request.amount(), request.reference(), link,
                            request.memberships().stream().map(item -> String.valueOf(item.year())).reduce((a, b) -> a + ", " + b).orElse("")));
            return true;
        } catch (final MailException e) {
            log.error("Could not send a payment link to spexare {}", spexare.id(), e);
            return false;
        }
    }

    public void receipt(final Spexare spexare, final PaymentView request) {
        send(spexare, mailer.text(LOCALE, "mail.payment.receipt.subject"),
                mailer.text(LOCALE, "mail.payment.receipt.body", request.amount(), request.reference(),
                        request.memberships().stream().map(item -> item.lifetime() ? "livstid från " + item.year() : String.valueOf(item.year()))
                                .reduce((a, b) -> a + ", " + b).orElse("")));
    }

    private void send(final Spexare spexare, final String subject, final String body) {
        if (spexare.email() == null || spexare.email().isBlank()) {
            log.warn("Spexare {} has no email address, could not send \"{}\"", spexare.id(), subject);
            return;
        }
        try {
            mailer.send(spexare.email(), subject, body);
        } catch (final MailException e) {
            log.error("Could not send \"{}\" to spexare {}", subject, spexare.id(), e);
        }
    }
}
