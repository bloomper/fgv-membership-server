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

import nu.fgv.membership.server.board.AuditLogService;
import nu.fgv.membership.server.board.AuditService;
import nu.fgv.membership.server.payment.PaymentSettings;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * Administration (spec 5.8): settings, maintenance jobs and the audit log.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
@PreAuthorize("hasRole('fgv-membership_ADMIN')")
public class AdminGraphqlApi {

    private final PaymentSettings settings;
    private final MaintenanceService maintenance;
    private final AuditLogService auditLog;
    private final AuditService audit;

    public AdminGraphqlApi(final PaymentSettings settings, final MaintenanceService maintenance, final AuditLogService auditLog, final AuditService audit) {
        this.settings = settings;
        this.maintenance = maintenance;
        this.auditLog = auditLog;
        this.audit = audit;
    }

    public record SettingsInput(String swishNumber, String bankAccount, String payeeName, int reminderDays, String recipients) {
    }

    @QueryMapping
    public PaymentSettings.Values settings() {
        return settings.values();
    }

    @MutationMapping
    public PaymentSettings.Values saveSettings(@AuthenticationPrincipal final Jwt caller, @Argument final SettingsInput input) {
        final PaymentSettings.Values saved = settings.save(input.swishNumber(), input.bankAccount(), input.payeeName(), input.reminderDays(), input.recipients());

        audit.log(caller.getSubject(), "SETTINGS_SAVED", null, "Swish %s, bank account %s, remind after %d days, notify %s".formatted(
                saved.swishNumber(), saved.bankAccount(), saved.reminderDays(), String.join(",", saved.recipients())));
        return saved;
    }

    @QueryMapping
    public List<MaintenanceService.Job> maintenanceJobs() {
        return maintenance.jobs();
    }

    @MutationMapping
    public MaintenanceService.Job runMaintenanceJob(@AuthenticationPrincipal final Jwt caller, @Argument final String name) {
        final MaintenanceService.Job job = maintenance.run(name);

        audit.log(caller.getSubject(), "JOB_RUN", name, job.lastResult());
        return job;
    }

    @QueryMapping
    public List<AuditLogService.Line> auditLog(@Argument final Integer first, @Argument final Integer max) {
        return auditLog.page(first == null ? 0 : first, max == null ? 50 : max);
    }
}
