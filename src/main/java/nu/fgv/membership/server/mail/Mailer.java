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

package nu.fgv.membership.server.mail;

import nu.fgv.membership.server.config.MembershipProperties;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Sends plain text emails through FGV's mail relay, with texts from {@code messages_sv.properties} and
 * {@code messages_en.properties}; Swedish unless the user's language is English.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class Mailer {

    private final JavaMailSender sender;
    private final MessageSource messages;
    private final MembershipProperties properties;

    public Mailer(final JavaMailSender sender, final MessageSource messages, final MembershipProperties properties) {
        this.sender = sender;
        this.messages = messages;
        this.properties = properties;
    }

    public static Locale localeOf(final String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en") ? Locale.ENGLISH : Locale.of("sv");
    }

    public String text(final Locale locale, final String key, final Object... arguments) {
        return messages.getMessage(key, arguments, locale);
    }

    /**
     * @throws org.springframework.mail.MailException if the relay refuses
     */
    public void send(final String to, final String subject, final String body) {
        final SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(properties.mail().from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
