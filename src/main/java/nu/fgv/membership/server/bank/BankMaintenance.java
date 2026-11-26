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

package nu.fgv.membership.server.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes bank data that is kept longer than it should be.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class BankMaintenance {

    private static final Logger log = LoggerFactory.getLogger(BankMaintenance.class);

    private final BankService service;

    public BankMaintenance(final BankService service) {
        this.service = service;
    }

    @Scheduled(cron = "${membership.jobs.bank-cleanup.cron-expression:0 40 3 * * *}", zone = "Europe/Stockholm")
    public void removeOld() {
        try {
            final int removed = service.removeOld();

            if (removed > 0) {
                log.info("Removed {} old bank transactions", removed);
            }
        } catch (final RuntimeException e) {
            log.error("Could not remove old bank transactions", e);
        }
    }
}
