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

package nu.fgv.membership.server.registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes accounts that were never verified.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class RegistrationMaintenance {

    private static final Logger log = LoggerFactory.getLogger(RegistrationMaintenance.class);

    private final RegistrationService service;

    public RegistrationMaintenance(final RegistrationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${membership.jobs.unverified-registrations.cron-expression:0 20 3 * * *}", zone = "Europe/Stockholm")
    public void removeUnverified() {
        final int removed = service.removeUnverified();

        if (removed > 0) {
            log.info("Removed {} accounts that were never verified", removed);
        }
    }
}
