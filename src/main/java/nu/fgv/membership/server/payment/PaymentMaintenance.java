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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reminds payers who have not paid or said that they did.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class PaymentMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PaymentMaintenance.class);

    private final PaymentService service;

    public PaymentMaintenance(final PaymentService service) {
        this.service = service;
    }

    @Scheduled(cron = "${membership.jobs.payment-reminders.cron-expression:0 10 * * * *}", zone = "Europe/Stockholm")
    public void remind() {
        try {
            final int reminded = service.remind();

            if (reminded > 0) {
                log.info("Reminded {} payers", reminded);
            }
        } catch (final RuntimeException e) {
            log.error("Could not remind payers", e);
        }
    }
}
