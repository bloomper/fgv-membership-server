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

package nu.fgv.membership.server.board;

import nu.fgv.membership.server.keycloak.KeycloakAdmin;
import nu.fgv.membership.server.keycloak.KeycloakUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who changed what, and when, for everyone: the audit entries and the changes to users' access, newest first. People are
 * named from Keycloak when the page is read; only their ids are stored here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AuditLogService {

    private final JdbcClient jdbc;
    private final KeycloakAdmin keycloak;

    public AuditLogService(final JdbcClient jdbc, final KeycloakAdmin keycloak) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
    }

    /**
     * @param actorName null when the account is gone or Keycloak cannot be reached
     */
    public record Line(String at, String actor, String actorName, String action, String subject, String detail) {
    }

    @Transactional(readOnly = true)
    public List<Line> page(final int first, final int max) {
        final List<Line> lines = jdbc.sql("""
                        SELECT at, actor, action, subject, detail FROM (
                            SELECT at, actor, action, subject, detail FROM audit_entry
                            UNION ALL
                            SELECT at, changed_by, CONCAT('ACCESS_', action), user_id,
                                   CONCAT_WS(' ', client_id, CONCAT_WS(' -> ', roles_before, roles_after)) FROM access_change
                        ) all_changes ORDER BY at DESC LIMIT :max OFFSET :first""")
                .param("max", Math.clamp(max, 1, 200)).param("first", Math.max(0, first))
                .query((rs, i) -> new Line(rs.getTimestamp("at").toInstant().toString(), rs.getString("actor"), null, rs.getString("action"),
                        rs.getString("subject"), rs.getString("detail")))
                .list();
        final Map<String, String> names = new HashMap<>();

        return lines.stream().map(line -> new Line(line.at(), line.actor(), names.computeIfAbsent(line.actor(), this::nameOf), line.action(),
                line.subject(), line.detail())).toList();
    }

    private String nameOf(final String id) {
        try {
            return keycloak.findUser(id).map(KeycloakUser::displayName).orElse("");
        } catch (final RuntimeException e) {
            return "";
        }
    }
}
