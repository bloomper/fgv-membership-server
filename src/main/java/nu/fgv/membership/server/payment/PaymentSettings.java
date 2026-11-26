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

import nu.fgv.membership.server.external.AppSetting;
import nu.fgv.membership.server.external.AppSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What admins set (spec 5.8): FGV's Swish number and bank account, who is told about payments, and how long an open
 * request waits before the payer is reminded. A value that has been set here wins; otherwise the configuration's value
 * is used, so a fresh install works from its environment alone.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class PaymentSettings {

    public static final String SWISH_NUMBER = "payment.swish-number";
    public static final String BANK_ACCOUNT = "payment.bank-account";
    public static final String PAYEE_NAME = "payment.payee-name";
    public static final String REMINDER_DAYS = "payment.reminder-days";
    public static final String NOTIFY_RECIPIENTS = "notify.recipients";

    private final PaymentProperties defaults;
    private final AppSettingRepository stored;
    private final List<String> defaultRecipients;

    @Autowired
    public PaymentSettings(final PaymentProperties defaults, final AppSettingRepository stored,
                           @Value("${membership.registration.notify-recipients:}") final List<String> defaultRecipients) {
        this.defaults = defaults;
        this.stored = stored;
        this.defaultRecipients = defaultRecipients;
    }

    /**
     * Settings that come from the configuration only, for tests.
     */
    public static PaymentSettings fixed(final PaymentProperties defaults, final List<String> recipients) {
        return new PaymentSettings(defaults, null, recipients);
    }

    /**
     * @param swishNumber    FGV's Swish number
     * @param bankAccount    the bank account shown for transfers
     * @param payeeName      the payee's name
     * @param reminderDays   days an open request waits before the payer is reminded once
     * @param recipients     who is emailed about payments and new registrations
     */
    public record Values(String swishNumber, String bankAccount, String payeeName, int reminderDays, List<String> recipients) {
    }

    public String swishNumber() {
        return get(SWISH_NUMBER).orElse(defaults.swishNumber());
    }

    public String bankAccount() {
        return get(BANK_ACCOUNT).orElse(defaults.bankAccount());
    }

    public String payeeName() {
        return get(PAYEE_NAME).orElse(defaults.payeeName());
    }

    public Duration reminderAfter() {
        return get(REMINDER_DAYS).map(days -> Duration.ofDays(Integer.parseInt(days))).orElse(defaults.reminderAfter());
    }

    public String qrUrl() {
        return defaults.qrUrl();
    }

    public String membershipType() {
        return defaults.membershipType();
    }

    /**
     * @return who is emailed about payments; the people who register are the same
     */
    public List<String> notifyRecipients() {
        return get(NOTIFY_RECIPIENTS).map(PaymentSettings::split).orElse(split(String.join(",", defaultRecipients)));
    }

    @Transactional(readOnly = true)
    public Values values() {
        return new Values(swishNumber(), bankAccount(), payeeName(), Math.toIntExact(reminderAfter().toDays()), notifyRecipients());
    }

    /**
     * @param reminderDays between 1 and 60
     * @param recipients   email addresses, comma or line separated
     */
    @Transactional
    public Values save(final String swishNumber, final String bankAccount, final String payeeName, final int reminderDays, final String recipients) {
        if (reminderDays < 1 || reminderDays > 60) {
            throw new IllegalArgumentException("Remind after 1 to 60 days");
        }
        for (final String address : split(recipients)) {
            if (!address.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new IllegalArgumentException(address + " is not an email address");
            }
        }
        put(SWISH_NUMBER, swishNumber);
        put(BANK_ACCOUNT, bankAccount);
        put(PAYEE_NAME, payeeName);
        put(REMINDER_DAYS, String.valueOf(reminderDays));
        put(NOTIFY_RECIPIENTS, String.join(",", split(recipients)));
        return values();
    }

    private Optional<String> get(final String name) {
        return stored == null ? Optional.empty() : stored.findById(name).map(AppSetting::getValue).filter(value -> !value.isBlank());
    }

    private void put(final String name, final String value) {
        if (value == null || value.isBlank()) {
            stored.findById(name).ifPresent(stored::delete);
        } else {
            stored.save(new AppSetting(name, value.strip()));
        }
    }

    static List<String> split(final String text) {
        return text == null ? List.of() : Arrays.stream(text.split("[,;\\s]+")).map(String::strip).filter(address -> !address.isEmpty()).toList();
    }
}
