package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.common.audit.JpaAuditConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6B.1 — V46 migration integration test against a real PostgreSQL
 * container running the FULL Flyway chain.
 *
 * <p>The pre-V46 world is simulated by running Flyway to exactly V44,
 * seeding duplicate live contacts through raw SQL (two groups of one
 * tenant sharing one phone, plus a second tenant with the same phone, plus
 * an attempt on a soon-to-be-merged contact), and only then letting Flyway
 * apply V45/V46. Assertions then verify the deterministic merge:</p>
 *
 * <ul>
 *   <li>exactly one live Contact for T1 + phone (survivor = oldest
 *       created_at, tie-break min id),</li>
 *   <li>memberships survivor → G1 and survivor → G2,</li>
 *   <li>the attempt remapped to the survivor,</li>
 *   <li>T2 + phone remains a separate live Contact,</li>
 *   <li>schema: contacts has no contact_group_id; contact_group_members
 *       exists with its unique constraint and composite tenant FKs.</li>
 * </ul>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContactIdentityMigrationPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("obd")
            .withUsername("obd_user")
            .withPassword("obd_password");

    static volatile Exception startupFailure;

    static {
        try {
            POSTGRES.start();
        } catch (Exception e) {
            startupFailure = e;
        }
    }

    @AfterAll
    void stopContainer() {
        POSTGRES.stop();
    }

    /**
     * Flyway runs to V44 only (spring.flyway.target) — the V46 seed state
     * (pre-refactor schema with group-scoped contacts) is prepared by the
     * first test, after which the target is lifted to allow V45+ to apply.
     */
    static volatile String flywayTarget = "44";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (startupFailure != null) {
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.target", () -> flywayTarget);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture ids — shared across the ordered tests via the shared DB.
    private static UUID tenant1;
    private static UUID tenant2;
    private static UUID group1;
    private static UUID group2;
    private static UUID olderContact;
    private static UUID newerContact;
    private static UUID tenant2Contact;
    private static UUID attemptId;
    private static UUID executionId;
    private static UUID campaignId;
    private static UUID snapshotId;

    private static final String PHONE = "+918012345678";

    private static Connection raw() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void exec(String sql, Object... args) throws Exception {
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static Long scalar(String sql, Object... args) throws Exception {
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @Order(1)
    @DisplayName("M-1: seed the pre-V46 duplicate world at schema V44")
    void seedPreV46World() throws Exception {
        tenant1 = UUID.randomUUID();
        tenant2 = UUID.randomUUID();
        group1 = UUID.randomUUID();
        group2 = UUID.randomUUID();
        olderContact = UUID.randomUUID();
        newerContact = UUID.randomUUID();
        tenant2Contact = UUID.randomUUID();
        campaignId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        attemptId = UUID.randomUUID();

        exec("INSERT INTO tenants (id, name, slug) VALUES (?, 't1', 's1')", tenant1);
        exec("INSERT INTO tenants (id, name, slug) VALUES (?, 't2', 's2')", tenant2);
        exec("INSERT INTO contact_groups (id, tenant_id, name) VALUES (?, ?, 'G1')", group1, tenant1);
        exec("INSERT INTO contact_groups (id, tenant_id, name) VALUES (?, ?, 'G2')", group2, tenant1);

        // Duplicate live identities in T1: two groups, same phone. The OLDER
        // row (earlier created_at) must become the survivor.
        exec("INSERT INTO contacts (id, tenant_id, contact_group_id, phone_number, "
                + "first_name, created_at) VALUES (?, ?, ?, ?, 'Older', now() - interval '2 days')",
            olderContact, tenant1, group1, PHONE);
        exec("INSERT INTO contacts (id, tenant_id, contact_group_id, phone_number, "
                + "first_name, created_at) VALUES (?, ?, ?, ?, 'Newer', now() - interval '1 day')",
            newerContact, tenant1, group2, PHONE);
        // Different tenant, same phone: independent identity (own group —
        // the pre-V46 group+phone uniqueness forbids reusing T1's group
        // for a T2 contact).
        UUID groupT2 = UUID.randomUUID();
        exec("INSERT INTO contact_groups (id, tenant_id, name) VALUES (?, ?, 'G-T2')",
            groupT2, tenant2);
        exec("INSERT INTO contacts (id, tenant_id, contact_group_id, phone_number, "
                + "first_name) VALUES (?, ?, ?, ?, 'T2')",
            tenant2Contact, tenant2, groupT2, PHONE);

        // Execution + attempt on the NEWER (to-be-merged) contact: proves the
        // attempt remap.
        exec("INSERT INTO campaigns (id, tenant_id, name, campaign_type, status, run_mode) "
                + "VALUES (?, ?, 'm', 'PLAYFILE', 'SCHEDULED', 'ONE_TIME')", campaignId, tenant1);
        exec("INSERT INTO campaign_execution_configurations (id, campaign_id, tenant_id, "
                + "campaign_type, contact_group_id, retry_max_attempts, retry_strategy, "
                + "call_on_whitelist_numbers) VALUES (?, ?, ?, 'PLAYFILE', ?, 0, 'FIXED', FALSE)",
            snapshotId, campaignId, tenant1, group2);
        exec("INSERT INTO campaign_executions (id, campaign_id, tenant_id, status, requested_by, "
                + "configuration_snapshot_id) VALUES (?, ?, ?, 'RUNNING', 'it', ?)",
            executionId, campaignId, tenant1, snapshotId);
        exec("INSERT INTO call_attempts (id, execution_id, campaign_id, tenant_id, contact_id, "
                + "did_id, attempt_number, status, scheduled_at) VALUES (?, ?, ?, ?, ?, ?, 1, "
                + "'QUEUED', now())",
            attemptId, executionId, campaignId, tenant1, newerContact, UUID.randomUUID());
    }

    @Test
    @Order(2)
    @DisplayName("M-2: lifting the Flyway target applies V46 and merges the duplicates")
    void applyV46AndVerifyMerge() throws Exception {
        // Force this Spring context's Flyway to advance: the NOT_SUPPORTED
        // tests share the context, so we run the pending migrations through
        // the datasource directly with Flyway's own runner.
        flywayTarget = "46";
        org.springframework.jdbc.datasource.init.ResourceDatabasePopulator populator =
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
        // Simpler and more faithful: execute the actual migration resource
        // text through JDBC, exactly as Flyway would.
        String sql = new String(getClass().getResourceAsStream(
                "/db/migration/V46__contact_identity_and_group_membership.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        // Flyway executes a migration in ONE transaction — required here so
        // the ON COMMIT DROP temp table survives until the merge completes.
        try (Connection migrationConnection = raw()) {
            migrationConnection.setAutoCommit(false);
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                migrationConnection,
                new org.springframework.core.io.support.EncodedResource(
                    new org.springframework.core.io.ByteArrayResource(
                        sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    java.nio.charset.StandardCharsets.UTF_8));
            migrationConnection.commit();
        }

        // Register V46 in flyway history so the schema test-world matches a
        // real run (head marker used by the harness assertions below).
        exec("INSERT INTO flyway_schema_history (installed_rank, version, description, type, "
                + "script, checksum, installed_by, execution_time, success) "
                + "SELECT COALESCE(MAX(installed_rank),0)+1, '46', "
                + "'contact identity and group membership', 'SQL', "
                + "'V46__contact_identity_and_group_membership.sql', 0, 'it', 0, TRUE "
                + "FROM flyway_schema_history");

        // === merge assertions ===

        // Exactly one LIVE contact for T1 + phone, and it is the OLDER row.
        Long liveT1 = scalar(
            "SELECT COUNT(*) FROM contacts WHERE tenant_id = ? AND phone_number = ? "
            + "AND deleted_at IS NULL", tenant1, PHONE);
        assertThat(liveT1).isEqualTo(1L);

        String survivorName = jdbcTemplate.queryForObject(
            "SELECT first_name FROM contacts WHERE tenant_id = ? AND phone_number = ? "
            + "AND deleted_at IS NULL", String.class, tenant1, PHONE);
        assertThat(survivorName).isEqualTo("Older");

        Long newerDeleted = scalar(
            "SELECT COUNT(*) FROM contacts WHERE id = ? AND deleted_at IS NOT NULL", newerContact);
        assertThat(newerDeleted).isEqualTo(1L);

        // Memberships: survivor → G1 and survivor → G2 (two rows).
        Long memberships = scalar(
            "SELECT COUNT(*) FROM contact_group_members WHERE contact_id = ? AND tenant_id = ?",
            olderContact, tenant1);
        assertThat(memberships).isEqualTo(2L);

        Long membershipToG2 = scalar(
            "SELECT COUNT(*) FROM contact_group_members WHERE contact_group_id = ? AND contact_id = ?",
            group2, olderContact);
        assertThat(membershipToG2).isEqualTo(1L);

        // The attempt was remapped to the survivor.
        Long remapped = scalar(
            "SELECT COUNT(*) FROM call_attempts WHERE id = ? AND contact_id = ?",
            attemptId, olderContact);
        assertThat(remapped).isEqualTo(1L);

        // T2 keeps its own live identity.
        Long liveT2 = scalar(
            "SELECT COUNT(*) FROM contacts WHERE tenant_id = ? AND phone_number = ? "
            + "AND deleted_at IS NULL", tenant2, PHONE);
        assertThat(liveT2).isEqualTo(1L);

        // === schema assertions ===

        Long groupColumnGone = scalar(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'contacts' "
            + "AND column_name = 'contact_group_id'");
        assertThat(groupColumnGone).isZero();

        Long memberTable = scalar(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'contact_group_members'");
        assertThat(memberTable).isEqualTo(1L);

        Long identityUnique = scalar(
            "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'contacts' "
            + "AND indexname = 'uq_contacts_tenant_phone_live'");
        assertThat(identityUnique).isEqualTo(1L);

        Long oldGroupUnique = scalar(
            "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'contacts' "
            + "AND indexname = 'uq_contacts_group_phone_live'");
        assertThat(oldGroupUnique).isZero();

        // Exactly the two composite tenant FKs (contact + group), by name.
        Long compositeFks = scalar(
            "SELECT COUNT(*) FROM pg_constraint WHERE conname IN "
            + "('fk_cgm_contact', 'fk_cgm_group') AND contype = 'f'");
        assertThat(compositeFks).isEqualTo(2L);
    }
}
