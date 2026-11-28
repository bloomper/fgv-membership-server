package nu.fgv.membership.server;

import nu.fgv.membership.server.imports.MembershipImport;
import nu.fgv.membership.server.imports.MembershipImport.Item;
import nu.fgv.membership.server.imports.MembershipImport.Outcome;
import nu.fgv.membership.server.membership.MembershipHow;
import nu.fgv.membership.server.membership.MembershipService;
import nu.fgv.membership.server.membership.MembershipStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spexregister 1.x's FGV memberships, carried in by spexregister-migration (spec 5.9): PAID and MIGRATED with a line in
 * the feed, without duplicates on a second run, leaving alone what the board changed, and only for IMPORT.
 */
class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String OTHER_SPEXARE = "0197d6a0-0000-7000-8000-000000000002";

    @Autowired
    private MembershipImport importer;
    @Autowired
    private MembershipService memberships;

    private static List<MembershipImport.Record> records() {
        return List.of(new MembershipImport.Record("test:membership:1:2019", SPEXARE_ID, 2019),
                new MembershipImport.Record("test:membership:1:2020", SPEXARE_ID, 2020));
    }

    private static List<Outcome> outcomes(final List<Item> items) {
        return items.stream().map(Item::outcome).toList();
    }

    private long count(final String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    @Test
    void migratedMembershipsArePaidAndReachSpexregistersFeed() {
        assertThat(outcomes(importer.run(records(), false, "fgv-spexregister-migration"))).containsExactly(Outcome.CREATED, Outcome.CREATED);

        assertThat(count("SELECT COUNT(*) FROM membership WHERE status = 'PAID' AND how = 'MIGRATED' AND NOT lifetime")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM membership_change WHERE kind = 'UPSERT'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM audit_entry WHERE action = 'MIGRATED' AND actor = 'fgv-spexregister-migration'")).isEqualTo(2);

        assertThat(outcomes(importer.run(records(), false, "fgv-spexregister-migration"))).containsExactly(Outcome.UNCHANGED, Outcome.UNCHANGED);
        assertThat(count("SELECT COUNT(*) FROM membership")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM membership_change")).isEqualTo(2);
    }

    @Test
    void aDryRunKeepsNothing() {
        assertThat(outcomes(importer.run(records(), true, "fgv-spexregister-migration"))).containsExactly(Outcome.CREATED, Outcome.CREATED);

        assertThat(count("SELECT COUNT(*) FROM membership")).isZero();
        assertThat(count("SELECT COUNT(*) FROM membership_change")).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_key")).isZero();
    }

    @Test
    void whatTheBoardChangedOrAlreadyHadIsKept() {
        memberships.record(OTHER_SPEXARE, "FGV", 2021, false, MembershipStatus.PAID, MembershipHow.SWISH, null);
        importer.run(records(), false, "fgv-spexregister-migration");

        final String id = jdbcClient.sql("SELECT id FROM membership WHERE spexare_id = :s AND membership_year = 2019")
                .param("s", SPEXARE_ID).query(String.class).single();

        memberships.record(SPEXARE_ID, "FGV", 2019, true, MembershipStatus.PAID, MembershipHow.MIGRATED, "Life member");

        final List<Item> again = importer.run(List.of(records().getFirst(), records().get(1),
                new MembershipImport.Record("test:membership:2:2021", OTHER_SPEXARE, 2021)), false, "fgv-spexregister-migration");

        assertThat(outcomes(again)).containsExactly(Outcome.KEPT, Outcome.UNCHANGED, Outcome.KEPT);
        assertThat(again.getFirst().id()).isEqualTo(id);
        assertThat(count("SELECT COUNT(*) FROM membership WHERE how = 'SWISH'")).isEqualTo(1);

        assertThat(importer.forget("test:", "fgv-spexregister-migration")).as("the 2020 one only").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM membership")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM membership_change WHERE kind = 'DELETE'")).isEqualTo(1);
    }

    @Test
    void onlyTheImportRoleMayImport() {
        graphQl(userToken(ADMIN)).document("mutation { importForget(sourcePrefix: \"x\") }").execute()
                .errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        graphQl(serviceToken("fgv-spexregister-service")).document("mutation { importForget(sourcePrefix: \"x\") }").execute()
                .errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }
}
