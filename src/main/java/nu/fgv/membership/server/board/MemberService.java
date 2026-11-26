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

import nu.fgv.membership.server.payment.Spexare;
import nu.fgv.membership.server.payment.SpexregisterClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who is a member which year, with names read from Spexregister when asked for and never stored. A life membership counts
 * in its first year and every year after it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MemberService {

    private static final int NAME_BATCH = 500;
    private static final String COUNTS = "m.status IN ('PAID', 'WAIVED')";

    private final JdbcClient jdbc;
    private final SpexregisterClient spexregister;
    private final Clock clock;

    public MemberService(final JdbcClient jdbc, final SpexregisterClient spexregister, final Clock clock) {
        this.jdbc = jdbc;
        this.spexregister = spexregister;
        this.clock = clock;
    }

    /**
     * @param name           null when Spexregister could not be reached or no longer has the spexare
     * @param wasMemberLastYear whether they counted as a member the year before
     */
    public record Member(String membershipId, String spexareId, String name, String type, int year, boolean lifetime, String status, String how,
                         String note, boolean optedOut, boolean wasMemberLastYear) {
    }

    /**
     * @param members how many counted as members that year, life members included
     * @param lifetime of them, how many have a life membership
     * @param joined   who were not members the year before
     */
    public record Count(int year, int members, int lifetime, int joined) {
    }

    /**
     * The members of a year, by name. By default those who count (paid or granted); {@code status} shows another status
     * instead, e.g. those who have not paid yet.
     *
     * @param status       one of the membership statuses, or null for those who count
     * @param how          how they paid, or null
     * @param wasMemberLastYear true: only those who were members the year before; false: only those who were not; null: all
     */
    @Transactional(readOnly = true)
    public List<Member> members(final int year, final String status, final String how, final Boolean wasMemberLastYear) {
        final String previous = "EXISTS (SELECT 1 FROM membership p WHERE p.spexare_id = m.spexare_id AND p.type = m.type AND p.status IN ('PAID', 'WAIVED')"
                + " AND (p.membership_year = :last OR (p.lifetime AND p.membership_year <= :last)))";
        final StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.spexare_id, m.type, m.membership_year, m.lifetime, m.status, m.how, m.note,
                       (SELECT COUNT(*) FROM reminder_opt_out o WHERE o.spexare_id = m.spexare_id) AS opted_out,
                       %s AS last_year
                FROM membership m
                WHERE m.type = 'FGV' AND (m.membership_year = :year OR (m.lifetime AND m.membership_year <= :year))
                """.formatted(previous));

        sql.append(status == null ? " AND " + COUNTS : " AND m.status = :status");
        if (how != null) {
            sql.append(" AND m.how = :how");
        }
        if (wasMemberLastYear != null) {
            sql.append(wasMemberLastYear ? " AND " : " AND NOT ").append(previous);
        }

        final var query = jdbc.sql(sql.toString()).param("year", year).param("last", year - 1);
        final var withStatus = status == null ? query : query.param("status", status);
        final var withHow = how == null ? withStatus : withStatus.param("how", how);
        final List<Member> rows = withHow.query((rs, i) -> new Member(rs.getString("id"), rs.getString("spexare_id"), null, rs.getString("type"),
                rs.getInt("membership_year"), rs.getBoolean("lifetime"), rs.getString("status"), rs.getString("how"), rs.getString("note"),
                rs.getInt("opted_out") > 0, rs.getInt("last_year") > 0)).list();

        return named(rows);
    }

    /**
     * @return members in each year from the first there is data for to this one
     */
    @Transactional(readOnly = true)
    public List<Count> trend() {
        final int thisYear = Year.now(clock.withZone(ZoneId.of("Europe/Stockholm"))).getValue();
        final Integer first = jdbc.sql("SELECT MIN(membership_year) FROM membership WHERE type = 'FGV'").query(Integer.class).optional().orElse(null);
        final List<Count> counts = new ArrayList<>();

        for (int year = first == null ? thisYear : first; year <= thisYear; year++) {
            counts.add(count(year));
        }
        return counts;
    }

    Count count(final int year) {
        final String counted = "FROM membership m WHERE m.type = 'FGV' AND " + COUNTS + " AND (m.membership_year = :year OR (m.lifetime AND m.membership_year <= :year))";
        final int members = jdbc.sql("SELECT COUNT(DISTINCT m.spexare_id) " + counted).param("year", year).query(Integer.class).single();
        final int lifetime = jdbc.sql("SELECT COUNT(DISTINCT m.spexare_id) " + counted + " AND m.lifetime").param("year", year).query(Integer.class).single();
        final int joined = jdbc.sql("SELECT COUNT(DISTINCT m.spexare_id) " + counted + " AND NOT EXISTS (SELECT 1 FROM membership p WHERE p.spexare_id = m.spexare_id"
                + " AND p.type = m.type AND p.status IN ('PAID', 'WAIVED') AND (p.membership_year = :last OR (p.lifetime AND p.membership_year <= :last)))")
                .param("year", year).param("last", year - 1).query(Integer.class).single();

        return new Count(year, members, lifetime, joined);
    }

    private List<Member> named(final List<Member> rows) {
        final Map<String, String> names = new HashMap<>();
        final List<String> ids = rows.stream().map(Member::spexareId).distinct().toList();

        try {
            for (int i = 0; i < ids.size(); i += NAME_BATCH) {
                spexregister.byIds(ids.subList(i, Math.min(ids.size(), i + NAME_BATCH))).forEach(spexare -> names.put(spexare.id(), spexare.name()));
            }
        } catch (final IllegalStateException e) {
            // Spexregister is down: the list shows ids until it is back
            names.clear();
        }

        final Collator collator = Collator.getInstance(Locale.of("sv"));

        return rows.stream().map(row -> new Member(row.membershipId(), row.spexareId(), names.get(row.spexareId()), row.type(), row.year(), row.lifetime(),
                        row.status(), row.how(), row.note(), row.optedOut(), row.wasMemberLastYear()))
                .sorted(java.util.Comparator.comparing((Member row) -> row.name() == null ? "\uFFFF" + row.spexareId() : row.name(), collator)
                        .thenComparing(Member::year)).toList();
    }

    static Map<String, Spexare> byId(final List<Spexare> spexare) {
        return spexare.stream().collect(Collectors.toMap(Spexare::id, Function.identity()));
    }
}
