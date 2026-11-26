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

package nu.fgv.membership.server.maintenance;

import nu.fgv.membership.server.application.ApplicationService;
import nu.fgv.membership.server.bank.BankService;
import nu.fgv.membership.server.board.PersonalLinkRepository;
import nu.fgv.membership.server.external.AppSetting;
import nu.fgv.membership.server.external.AppSettingRepository;
import nu.fgv.membership.server.payment.PaymentService;
import nu.fgv.membership.server.registration.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * The maintenance jobs: each can run on its schedule and on demand, and says when it last ran and what it did.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    /**
     * @param lastRun    when it last ran, or null
     * @param lastResult what it did, e.g. "3 handled", or what went wrong
     */
    public record Job(String name, String description, String lastRun, String lastResult) {
    }

    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, IntSupplier> actions = new LinkedHashMap<>();
    private final AppSettingRepository settings;
    private final PersonalLinkRepository links;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public MaintenanceService(final PaymentService payments, final BankService bank, final RegistrationService registrations,
                              final ApplicationService applications, final PersonalLinkRepository links, final AppSettingRepository settings,
                              final Clock clock) {
        this.settings = settings;
        this.links = links;
        this.clock = clock;
        add("PAYMENT_REMINDERS", "Remind payers of open requests that are old enough (once each)", payments::remind);
        add("BANK_CLEANUP", "Remove unmatched bank transactions after 90 days and dismissed ones' fingerprints after a year", bank::removeOld);
        add("UNVERIFIED_REGISTRATIONS", "Remove accounts whose email address was never confirmed", registrations::removeUnverified);
        add("APPLICATION_CLEANUP", "Remove applications decided more than 12 months ago", applications::removeOld);
        add("EXPIRED_PERSONAL_LINKS", "Remove personal links that have expired", this::removeExpiredLinks);
    }

    private void add(final String name, final String description, final IntSupplier action) {
        descriptions.put(name, description);
        actions.put(name, action);
    }

    @Transactional(readOnly = true)
    public List<Job> jobs() {
        return descriptions.keySet().stream().map(name -> {
            final String last = settings.findById("job." + name).map(AppSetting::getValue).orElse(null);
            final int bar = last == null ? -1 : last.indexOf('|');

            return new Job(name, descriptions.get(name), bar < 0 ? null : last.substring(0, bar), bar < 0 ? null : last.substring(bar + 1));
        }).toList();
    }

    /**
     * Runs a job now and records how it went.
     */
    public Job run(final String name) {
        final IntSupplier action = actions.get(name);

        if (action == null) {
            throw new IllegalArgumentException("Unknown job " + name);
        }

        String result;

        try {
            result = action.getAsInt() + " handled";
        } catch (final RuntimeException e) {
            log.error("Maintenance job {} failed", name, e);
            result = "Failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        record(name, result.length() > 450 ? result.substring(0, 450) : result);
        return jobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow();
    }

    @Transactional
    void record(final String name, final String result) {
        final String value = clock.instant() + "|" + result;

        settings.findById("job." + name).ifPresentOrElse(setting -> setting.setValue(value), () -> settings.save(new AppSetting("job." + name, value)));
        settings.flush();
    }

    @Transactional
    int removeExpiredLinks() {
        return Math.toIntExact(links.deleteByExpiresAtBefore(clock.instant()));
    }

    @Scheduled(cron = "${membership.jobs.retention.cron-expression:0 50 3 * * *}", zone = "Europe/Stockholm")
    void retention() {
        run("APPLICATION_CLEANUP");
        run("EXPIRED_PERSONAL_LINKS");
    }
}
