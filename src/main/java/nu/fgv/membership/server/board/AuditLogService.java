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
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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

    /**
     * What the log is filtered by; each part may be null.
     *
     * @param action one action, e.g. PAYMENT_RECORDED
     * @param actor  who made the change, by account id
     * @param from   the first day, yyyy-mm-dd, in Swedish time
     * @param to     the last day, yyyy-mm-dd, in Swedish time
     * @param q      words that must all be in the subject or the details
     */
    public record Filter(@Nullable String action, @Nullable String actor, @Nullable String from, @Nullable String to, @Nullable String q) {

        static final Filter NONE = new Filter(null, null, null, null, null);
    }

    /**
     * @param name null when the account is gone or Keycloak cannot be reached
     */
    public record Actor(String id, @Nullable String name) {
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

    // Every change, from the audit entries and the changes to users' access, with the same columns
    private static final String ALL_CHANGES = """
            SELECT at, actor, action, subject, detail FROM audit_entry
            UNION ALL
            SELECT at, changed_by, CONCAT('ACCESS_', action), user_id,
                   CONCAT_WS(' ', client_id, CONCAT_WS(' -> ', roles_before, roles_after)) FROM access_change""";

    @Transactional(readOnly = true)
    public List<Line> page(final int first, final int max) {
        return page(first, max, Filter.NONE);
    }

    @Transactional(readOnly = true)
    public List<Line> page(final int first, final int max, final Filter filter) {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new HashMap<>();

        if (filter.action() != null && !filter.action().isBlank()) {
            where.add("action = :action");
            params.put("action", filter.action());
        }
        if (filter.actor() != null && !filter.actor().isBlank()) {
            where.add("actor = :actor");
            params.put("actor", filter.actor());
        }
        if (filter.from() != null && !filter.from().isBlank()) {
            where.add("at >= :from");
            params.put("from", Timestamp.from(LocalDate.parse(filter.from()).atStartOfDay(ZONE).toInstant()));
        }
        if (filter.to() != null && !filter.to().isBlank()) {
            where.add("at < :to");
            params.put("to", Timestamp.from(LocalDate.parse(filter.to()).plusDays(1).atStartOfDay(ZONE).toInstant()));
        }
        if (filter.q() != null) {
            final String[] words = filter.q().strip().toLowerCase(java.util.Locale.ROOT).split("\\s+");

            for (int i = 0; i < Math.min(words.length, 10); i++) {
                if (!words[i].isEmpty()) {
                    where.add("LOWER(CONCAT_WS(' ', subject, detail)) LIKE :q" + i);
                    params.put("q" + i, "%" + words[i].replace("%", "").replace("_", "") + "%");
                }
            }
        }
        params.put("max", Math.clamp(max, 1, 200));
        params.put("first", Math.max(0, first));

        final List<Line> lines = jdbc.sql("SELECT at, actor, action, subject, detail FROM (" + ALL_CHANGES + ") all_changes"
                        + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY at DESC LIMIT :max OFFSET :first")
                .params(params)
                .query((rs, i) -> new Line(rs.getTimestamp("at").toInstant().toString(), rs.getString("actor"), null, rs.getString("action"),
                        rs.getString("subject"), rs.getString("detail")))
                .list();
        final Map<String, String> names = new HashMap<>();

        return lines.stream().map(line -> new Line(line.at(), line.actor(), names.computeIfAbsent(line.actor(), this::nameOf), line.action(),
                line.subject(), line.detail())).toList();
    }

    /**
     * The actions that are in the log, for filtering on.
     */
    @Transactional(readOnly = true)
    public List<String> actions() {
        return jdbc.sql("SELECT DISTINCT action FROM (" + ALL_CHANGES + ") all_changes ORDER BY action").query(String.class).list();
    }

    /**
     * Everyone who has changed something, by name, for filtering on.
     */
    @Transactional(readOnly = true)
    public List<Actor> actors() {
        return jdbc.sql("SELECT DISTINCT actor FROM (" + ALL_CHANGES + ") all_changes").query(String.class).list().stream()
                .map(id -> new Actor(id, nameOf(id))).toList();
    }

    private String nameOf(final String id) {
        try {
            return keycloak.findUser(id).map(KeycloakUser::displayName).orElse("");
        } catch (final RuntimeException e) {
            return "";
        }
    }
}
