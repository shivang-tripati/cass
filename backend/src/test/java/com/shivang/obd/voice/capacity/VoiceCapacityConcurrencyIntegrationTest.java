package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-0 Phase 11 — real-PostgreSQL concurrency test.
 * <p>
 * Verifies that concurrent admission CANNOT exceed configured gateway
 * capacity when N workers race through the exact reservation primitive used
 * by {@code VoiceCapacityServiceImpl}: {@code pg_try_advisory_xact_lock} +
 * usage count + INSERT inside a single transaction per connection. The
 * advisory lock is NOT mocked — this is actual PostgreSQL behavior under
 * contention, with one connection per worker exactly like the app's Hikari
 * pool under parallel workers.
 * <p>
 * Runs against the same Testcontainer as
 * {@link VoiceSchemaMigrationIntegrationTest} (real Flyway-migrated schema).
 */
class VoiceCapacityConcurrencyIntegrationTest extends VoicePostgresIntegrationSupport {

    private static final int GATEWAY_CAPACITY = 10;
    private static final int WORKERS = 20;

    private static final UUID GATEWAY = UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID TENANT = UUID.fromString("cc000000-0000-4000-8000-000000000011");

    private Connection adminConnection;

    @BeforeEach
    void setUpAdminConnection() throws Exception {
        // Base-class @BeforeEach has already confirmed Flyway migrations; the
        // gateway and tenant seeds use the shared unique-name helpers.
        try (Connection c = rawConnection()) {
            VoicePostgresIntegrationSupport.insertGatewayRow(c, GATEWAY, GATEWAY_CAPACITY);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
        }
        adminConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        clearReservations();
    }

    @AfterEach
    void closeAdminConnection() throws Exception {
        clearReservations();
        if (adminConnection != null) {
            adminConnection.close();
        }
    }

    @AfterAll
    static void cleanupAll() throws Exception {
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM voice_channel_reservations WHERE gateway_id = ?")) {
            ps.setObject(1, GATEWAY);
            ps.executeUpdate();
        }
    }

    private void clearReservations() throws SQLException {
        try (PreparedStatement ps = adminConnection.prepareStatement(
                "DELETE FROM voice_channel_reservations WHERE gateway_id = ?")) {
            ps.setObject(1, GATEWAY);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("20 concurrent reservations against capacity 10: at most 10 succeed, never more")
    void concurrentReservations_neverExceedCapacity() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        AtomicInteger successes = new AtomicInteger();
        Set<UUID> admittedRows = ConcurrentHashMap.newKeySet();

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            futures.add(pool.submit((Callable<Boolean>) () -> {
                // One connection per worker: each reservation runs in its own
                // transaction, exactly like app workers on the Hikari pool.
                try (Connection conn = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                    boolean admitted = tryReserveAtomically(
                            conn, GATEWAY, TENANT, GATEWAY_CAPACITY, admittedRows);
                    if (admitted) {
                        successes.incrementAndGet();
                    }
                    return admitted;
                }
            }));
        }
        for (Future<Boolean> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Hard capacity invariant
        assertThat(successes.get())
                .as("successful reservations must not exceed gateway capacity")
                .isLessThanOrEqualTo(GATEWAY_CAPACITY);
        assertThat(activeCount(adminConnection, GATEWAY))
                .as("active rows must equal successful admissions")
                .isEqualTo(successes.get());
        assertThat(admittedRows)
                .as("no duplicate reservation rows may be created")
                .hasSize(successes.get());

        // Cleanup: release everything; no negative usage possible
        int released = releaseReservations(adminConnection, GATEWAY, TENANT);
        assertThat(released).isEqualTo(successes.get());
        assertThat(activeCount(adminConnection, GATEWAY)).isZero();
    }

    @Test
    @DisplayName("reservation is refused while another transaction holds the gateway lock")
    void advisoryLockActuallySerializes() throws Exception {
        // Fill to capacity - 1: exactly one slot remains.
        for (int i = 0; i < GATEWAY_CAPACITY - 1; i++) {
            insertReservation(GATEWAY, TENANT);
        }

        // Worker A holds the advisory lock inside an open transaction.
        try (Connection holder = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            long lockId = 0x100000000L + GATEWAY.hashCode();
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT pg_try_advisory_xact_lock(?)")) {
                lock.setLong(1, lockId);
                ResultSet rs = lock.executeQuery();
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }

            // Worker B attempts to reserve: must be refused on the lock.
            Set<UUID> b1 = ConcurrentHashMap.newKeySet();
            try (Connection conn = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                assertThat(tryReserveAtomically(conn, GATEWAY, TENANT, GATEWAY_CAPACITY, b1))
                        .as("reservation must be refused while another transaction holds the gateway lock")
                        .isFalse();
            }

            // Release A's lock (rollback ends its transaction).
            holder.rollback();
        }

        // B can proceed once the lock is free and a slot remains.
        Set<UUID> b2 = ConcurrentHashMap.newKeySet();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(tryReserveAtomically(conn, GATEWAY, TENANT, GATEWAY_CAPACITY, b2))
                    .as("reservation must succeed once the lock is free and a slot remains")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("full-capacity gateway rejects further reservations")
    void fullGateway_rejectsFurtherReservations() throws Exception {
        for (int i = 0; i < GATEWAY_CAPACITY; i++) {
            insertReservation(GATEWAY, TENANT);
        }
        Set<UUID> admitted = ConcurrentHashMap.newKeySet();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(tryReserveAtomically(conn, GATEWAY, TENANT, GATEWAY_CAPACITY, admitted))
                    .isFalse();
        }
        assertThat(activeCount(adminConnection, GATEWAY)).isEqualTo(GATEWAY_CAPACITY);
    }

    // ------------------------------------------------------------------
    // JDBC primitives (must mirror VoiceCapacityServiceImpl SQL exactly)
    // ------------------------------------------------------------------

    private void insertReservation(UUID gatewayId, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = adminConnection.prepareStatement(
                "INSERT INTO voice_channel_reservations (gateway_id, tenant_id) VALUES (?, ?)")) {
            ps.setObject(1, gatewayId);
            ps.setObject(2, tenantId);
            ps.executeUpdate();
        }
    }

    private static int activeCount(Connection c, UUID gatewayId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = ? AND released_at IS NULL")) {
            ps.setObject(1, gatewayId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int releaseReservations(Connection c, UUID gatewayId, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE voice_channel_reservations SET released_at = now() "
                        + "WHERE gateway_id = ? AND tenant_id = ? AND released_at IS NULL")) {
            ps.setObject(1, gatewayId);
            ps.setObject(2, tenantId);
            return ps.executeUpdate();
        }
    }

    /**
     * The reservation primitive exactly as issued by VoiceCapacityServiceImpl:
     * xact-scoped advisory try-lock, gateway-wide active count, INSERT — all
     * inside one transaction. Records admitted reservation ids so duplicate
     * inserts would surface as duplicate set members.
     */
    private static boolean tryReserveAtomically(
            Connection conn, UUID gatewayId, UUID tenantId, int gatewayLimit,
            Set<UUID> admittedIds) throws SQLException {
        conn.setAutoCommit(false);
        try {
            long lockId = 0x100000000L + gatewayId.hashCode();
            try (PreparedStatement lock = conn.prepareStatement(
                    "SELECT pg_try_advisory_xact_lock(?)")) {
                lock.setLong(1, lockId);
                ResultSet rs = lock.executeQuery();
                rs.next();
                if (!rs.getBoolean(1)) {
                    conn.rollback();
                    return false;
                }
            }
            int used;
            try (PreparedStatement count = conn.prepareStatement(
                    "SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = ? AND released_at IS NULL")) {
                count.setObject(1, gatewayId);
                ResultSet rs = count.executeQuery();
                rs.next();
                used = rs.getInt(1);
            }
            if (used >= gatewayLimit) {
                conn.rollback();
                return false;
            }
            UUID reservationId = UUID.randomUUID();
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO voice_channel_reservations (id, gateway_id, tenant_id) VALUES (?, ?, ?)")) {
                ins.setObject(1, reservationId);
                ins.setObject(2, gatewayId);
                ins.setObject(3, tenantId);
                ins.executeUpdate();
            }
            admittedIds.add(reservationId);
            conn.commit();
            return true;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
}
