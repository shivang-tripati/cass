package com.shivang.obd.voice.capacity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-PostgreSQL harness for the VB-0 integration tests.
 * <p>
 * Boots a stock {@code postgres:16-alpine} Testcontainer and lets Spring Boot
 * run the REAL Flyway migration chain (V1..V34) against it — no hand-rolled
 * schema. The context loaded is a {@link DataJpaTest} slice: real repositories,
 * real {@link jakarta.persistence.EntityManager}, real PostgreSQL, no Redis or
 * web stack needed by the voice capacity domain.
 * <p>
 * The container is started from a static initializer so it is up before Spring
 * resolves {@link DynamicPropertySource} values. If Docker is unavailable the
 * subclass tests are skipped (BLOCKED), never silently passed.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.context.annotation.Import(com.shivang.obd.common.audit.JpaAuditConfig.class)
public abstract class VoicePostgresIntegrationSupport {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("obd")
                    .withUsername("obd_user")
                    .withPassword("obd_password");

    /** Set when container startup failed (typically: Docker not running). */
    static volatile Exception startupFailure;

    static {
        try {
            POSTGRES.start();
        } catch (Exception e) {
            startupFailure = e;
        }
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway runs the real V1..V34 chain on the fresh container database.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Migrations are asserted by the schema test itself.
        registry.add("spring.flyway.validate-on-migrate", () -> "true");
    }

    private static volatile boolean migrationsConfirmed;

    @BeforeAll
    static void requireDocker() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                            + startupFailure.getMessage(),
                    startupFailure);
        }
    }

    /**
     * Ensures the Flyway chain has run before each test touches fixtures.
     * The Spring context (and therefore Flyway) initializes at instance setup,
     * AFTER static {@code @BeforeAll} methods — so the wait happens here.
     */
    @org.junit.jupiter.api.BeforeEach
    void awaitMigrationsOnce() throws Exception {
        if (migrationsConfirmed) {
            return;
        }
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = rawConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '33' AND success = TRUE");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    migrationsConfirmed = true;
                    return;
                }
            } catch (SQLException e) {
                // Table not created yet — keep waiting.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Flyway migrations did not complete within 120s");
    }

    /** Direct JDBC connection for information-schema assertions. */
    protected static Connection rawConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * Unique-safe gateway insert: sip_gateways has partial unique indexes on
     * name and free_switch_gateway_name for live rows, so each fixture gateway
     * gets a distinct name/FS name.
     */
    protected static void insertGatewayRow(Connection c, UUID gatewayId, int maxChannels) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sip_gateways (id, name, provider, free_switch_gateway_name, "
                        + "max_concurrent_channels, enabled) VALUES (?, ?, 'TATA', ?, ?, TRUE) "
                        + "ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, gatewayId);
            ps.setString(2, "gw-" + gatewayId);
            ps.setString(3, "fs-" + gatewayId);
            ps.setInt(4, maxChannels);
            ps.executeUpdate();
        }
    }

    /** Tenant fixture on the SAME connection (reservations FK-reference tenants). */
    protected static void insertTenantRow(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, tenantId);
            ps.setString(2, "tenant-" + tenantId);
            ps.setString(3, "tenant-" + tenantId);
            ps.executeUpdate();
        }
    }

    protected static int scalarInt(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    protected static boolean exists(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }
}
