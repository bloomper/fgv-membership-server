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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lists the board takes to the annual meeting and the treasurer to the bookkeeping, as Excel.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ExportService {

    private final MemberService members;
    private final JdbcClient jdbc;
    private final SpexregisterClient spexregister;

    public ExportService(final MemberService members, final JdbcClient jdbc, final SpexregisterClient spexregister) {
        this.members = members;
        this.jdbc = jdbc;
        this.spexregister = spexregister;
    }

    /**
     * The members of a year: name, type, year, and whether it is for life.
     */
    @Transactional(readOnly = true)
    public byte[] memberList(final int year) {
        final List<List<Object>> rows = new ArrayList<>();

        members.members(year, null, null, null).forEach(member -> rows.add(java.util.Arrays.asList(
                member.name() == null ? member.spexareId() : member.name(), member.type(), year, member.lifetime() ? "Livstid från " + member.year() : "")));
        return Xlsx.write("Medlemmar " + year, List.of("Namn", "Typ", "År", "Livstid"), rows);
    }

    /**
     * The payments of a year for the bookkeeping: date, amount, reference, member, how, and what it was matched to.
     */
    @Transactional(readOnly = true)
    public byte[] payments(final int year) {
        record Line(java.time.LocalDate paidOn, int amount, String reference, String spexareId, String how, String match, String note) {
        }

        final List<Line> lines = jdbc.sql("""
                        SELECT p.paid_on, p.amount, r.reference, r.spexare_id, p.how, p.note, t.file_name, p.transaction_pk
                        FROM payment p JOIN payment_request r ON r.pk = p.request_pk LEFT JOIN bank_transaction t ON t.pk = p.transaction_pk
                        WHERE YEAR(p.paid_on) = :year ORDER BY p.paid_on, p.pk""")
                .param("year", year)
                .query((rs, i) -> new Line(rs.getDate("paid_on").toLocalDate(), rs.getInt("amount"), rs.getString("reference"), rs.getString("spexare_id"),
                        rs.getString("how"), rs.getObject("transaction_pk") == null ? "Manuellt" : "Bank: " + rs.getString("file_name"), rs.getString("note")))
                .list();
        final Map<String, String> names = new HashMap<>();
        final List<String> ids = lines.stream().map(Line::spexareId).distinct().toList();

        for (int i = 0; i < ids.size(); i += 500) {
            spexregister.byIds(ids.subList(i, Math.min(ids.size(), i + 500))).forEach((Spexare spexare) -> names.put(spexare.id(), spexare.name()));
        }

        final List<List<Object>> rows = new ArrayList<>();

        lines.forEach(line -> rows.add(java.util.Arrays.asList(line.paidOn().toString(), line.amount(), line.reference(),
                names.getOrDefault(line.spexareId(), line.spexareId()), line.how(), line.match(), line.note())));
        return Xlsx.write("Betalningar " + year, List.of("Datum", "Belopp", "Referens", "Medlem", "Hur", "Matchning", "Anteckning"), rows);
    }
}
