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

    public PaymentMails(final Mailer mailer, final PaymentProperties properties,
                        @org.springframework.beans.factory.annotation.Value("${membership.web-url}") final String webUrl) {
        this.mailer = mailer;
        this.properties = properties;
        this.webUrl = webUrl;
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
