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

import nu.fgv.membership.server.config.MembershipProperties;
import nu.fgv.membership.server.mail.Mailer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PaymentMailsTest {

    private final PaymentSettings properties = PaymentSettings.fixed(new PaymentProperties("123", "456", "FGV", "http://qr", Duration.ofDays(3), "FGV"), List.of());
    private final JavaMailSender sender = mock(JavaMailSender.class);

    private PaymentMails mails(final String... recipients) {
        final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();

        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        final MembershipProperties membership = new MembershipProperties("https://medlem.example", null, null, null,
                new MembershipProperties.Mail("noreply@fgv.nu"), List.of(), null);

        return new PaymentMails(new Mailer(sender, messages, membership), PaymentSettings.fixed(new PaymentProperties("123", "456", "FGV", "http://qr", Duration.ofDays(3), "FGV"), List.of(recipients)), "https://medlem.example");
    }

    private List<SimpleMailMessage> sent(final int times) {
        final ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);

        verify(sender, times(times)).send(message.capture());
        return message.getAllValues();
    }

    @Test
    void everyAdministratorIsToldAboutAReportedPayment() {
        mails("a@fgv.nu", "b@fgv.nu").adminNotice(PaymentMails.Event.REPORTED, "Greta Garbo", 200, "FGV26ABCD", "2026", "Betalat av Partner");

        assertThat(sent(2)).extracting(message -> message.getTo()[0]).containsExactly("a@fgv.nu", "b@fgv.nu");
    }

    @Test
    void theMessageNamesWhoPaidHowMuchAndWhereToFindIt() {
        mails("a@fgv.nu").adminNotice(PaymentMails.Event.ONLINE, "Greta Garbo", 200, "FGV26ABCD", "2026", "Online: order-1");

        final SimpleMailMessage message = sent(1).getFirst();

        assertThat(message.getSubject()).contains("Greta Garbo");
        assertThat(message.getText()).contains("FGV26ABCD", "2026", "Online: order-1", "https://medlem.example/treasurer/payments");
    }

    @Test
    void withoutRecipientsNothingIsSent() {
        mails().adminNotice(PaymentMails.Event.REPORTED, "Greta Garbo", 200, "FGV26ABCD", "2026", null);

        verify(sender, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    @Test
    void aMailRelayThatRefusesDoesNotStopThePayment() {
        doThrow(new MailSendException("down")).when(sender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        assertThatCode(() -> mails("a@fgv.nu", "b@fgv.nu").adminNotice(PaymentMails.Event.REPORTED, "Greta", 200, "R", "2026", null))
                .doesNotThrowAnyException();
        sent(2);
    }
}
