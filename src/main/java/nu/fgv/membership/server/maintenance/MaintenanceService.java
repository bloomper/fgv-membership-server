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
import org.springframework.core.env.Environment;
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
     * @param schedule      when it runs by itself, as a cron expression
     * @param lastRun       when it last ran, or null
     * @param lastResult    what it did, e.g. "3 handled", or what went wrong
     * @param lastSucceeded whether the last run went well, or null when it never ran
     * @param handled       how many things the last run handled, or null when it failed or never ran
     */
    public record Job(String name, String description, String schedule, String lastRun, String lastResult, Boolean lastSucceeded,
                      Integer handled) {
    }

    private static final String FAILED = "Failed: ";
    private static final String HANDLED = " handled";

    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, IntSupplier> actions = new LinkedHashMap<>();
    private final Map<String, String> schedules = new LinkedHashMap<>();
    private final AppSettingRepository settings;
    private final PersonalLinkRepository links;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public MaintenanceService(final PaymentService payments, final BankService bank, final RegistrationService registrations,
                              final ApplicationService applications, final PersonalLinkRepository links, final AppSettingRepository settings,
                              final Clock clock, final Environment environment) {
        this.settings = settings;
        this.links = links;
        this.clock = clock;
        // The schedules are those of the @Scheduled methods that run the jobs, with the same defaults
        final String retention = environment.getProperty("membership.jobs.retention.cron-expression", "0 50 3 * * *");

        add("PAYMENT_REMINDERS", "Remind payers of open requests that are old enough (once each)", payments::remind,
                environment.getProperty("membership.jobs.payment-reminders.cron-expression", "0 10 * * * *"));
        add("BANK_CLEANUP", "Remove unmatched bank transactions after 90 days and dismissed ones' fingerprints after a year", bank::removeOld,
                environment.getProperty("membership.jobs.bank-cleanup.cron-expression", "0 40 3 * * *"));
        add("UNVERIFIED_REGISTRATIONS", "Remove accounts whose email address was never confirmed", registrations::removeUnverified,
                environment.getProperty("membership.jobs.unverified-registrations.cron-expression", "0 20 3 * * *"));
        add("APPLICATION_CLEANUP", "Remove applications decided more than 12 months ago", applications::removeOld, retention);
        add("EXPIRED_PERSONAL_LINKS", "Remove personal links that have expired", () -> Math.toIntExact(links.deleteByExpiresAtBefore(clock.instant())),
                retention);
    }

    private void add(final String name, final String description, final IntSupplier action, final String schedule) {
        descriptions.put(name, description);
        actions.put(name, action);
        schedules.put(name, schedule);
    }

    @Transactional(readOnly = true)
    public List<Job> jobs() {
        return descriptions.keySet().stream().map(name -> {
            final String last = settings.findById("job." + name).map(AppSetting::getValue).orElse(null);
            final int bar = last == null ? -1 : last.indexOf('|');

            final String result = bar < 0 ? null : last.substring(bar + 1);

            return new Job(name, descriptions.get(name), schedules.get(name), bar < 0 ? null : last.substring(0, bar), result,
                    result == null ? null : !result.startsWith(FAILED), handled(result));
        }).toList();
    }

    /**
     * The number in a result such as "3 handled", or null for a failure.
     */
    static Integer handled(final String result) {
        if (result == null || !result.endsWith(HANDLED)) {
            return null;
        }
        try {
            return Integer.valueOf(result.substring(0, result.length() - HANDLED.length()));
        } catch (final NumberFormatException e) {
            return null;
        }
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
            result = action.getAsInt() + HANDLED;
        } catch (final RuntimeException e) {
            log.error("Maintenance job {} failed", name, e);
            result = FAILED + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        record(name, result.length() > 450 ? result.substring(0, 450) : result);
        return jobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * Saves when a job last ran and what it did. Saving a setting that has the same name replaces it; this is not done
     * by changing a loaded one, because this method is not run in a transaction of its own.
     */
    void record(final String name, final String result) {
        settings.save(new AppSetting("job." + name, clock.instant() + "|" + result));
    }

    @Scheduled(cron = "${membership.jobs.retention.cron-expression:0 50 3 * * *}", zone = "Europe/Stockholm")
    void retention() {
        run("APPLICATION_CLEANUP");
        run("EXPIRED_PERSONAL_LINKS");
    }
}
