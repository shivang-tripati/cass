package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-0 Phases 5/6 at the database level — reservation lifecycle and
 * reconciliation semantics against real PostgreSQL rows.
 * <p>
 * The SQL statements are exactly the ones issued by
 * {@code VoiceCapacityServiceImpl} (reserve INSERT, release UPDATE with
 * {@code released_at IS NULL} predicate, reconciliation UPDATE with
 * {@code reserved_at < :cutoff} predicate).
 */
class VoiceReservationLifecycleIntegrationTest extends VoicePostgresIntegrationSupport {

    private static final UUID TENANT = UUID.fromString("ee000000-0000-4000-8000-00000000000a");
    private static final UUID GATEWAY = UUID.fromString("ee000000-0000-4000-8000-000000000001");

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("RES4: double release is idempotent — second UPDATE touches zero rows, no negative usage")
    void res4_doubleReleaseIsIdempotent() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-0000000000r1"
                .replace("r", "1"));
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
            insertRes(c, gateway, TENANT);

            // First release: touches the active row.
            assertThat(release(c, gateway)).isEqualTo(1);
            // Second release: released_at IS NULL predicate matches nothing.
            assertThat(release(c, gateway)).isZero();
            // No negative usage: active count is 0, total rows still 1.
            assertThat(activeCount(c, gateway)).isZero();
            assertThat(totalCount(c, gateway)).isEqualTo(1);
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("RES5: duplicate hangup events only touch active rows (idempotent)")
    void res5_duplicateHangupsTouchActiveRowsOnly() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-000000000112");
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
            // Two active reservations (a call may hold one per leg situation is
            // not modeled; both active) then two release events.
            insertRes(c, gateway, TENANT);
            insertRes(c, gateway, TENANT);

            int first = release(c, gateway);
            int second = release(c, gateway);
            assertThat(first).isEqualTo(2);
            assertThat(second).isZero();
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("RES6: release for unknown gateway/tenant is a safe no-op")
    void res6_releaseUnknownIdsIsSafeNoOp() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-000000000113");
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
            // Unknown tenant on a known gateway, or completely unknown ids:
            // release touches nothing and does not error.
            assertThat(releaseForTenant(c, gateway, UUID.randomUUID())).isZero();
            assertThat(releaseForTenant(c, UUID.randomUUID(), UUID.randomUUID())).isZero();
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("REC1: stale reservation (older than cutoff, active) is released")
    void rec1_staleReservationIsReleased() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-000000000114");
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
            // Reserved 10 minutes ago, still active.
            insertResAt(c, gateway, TENANT, Instant.now().minusSeconds(600));

            int reconciled = reconcile(c, Instant.now().minusSeconds(300));
            assertThat(reconciled).isEqualTo(1);
            assertThat(activeCount(c, gateway)).isZero();
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("REC2: recent reservation remains active after reconciliation")
    void rec2_recentReservationRemainsActive() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-000000000115");
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
            insertResAt(c, gateway, TENANT, Instant.now().minusSeconds(30));

            int reconciled = reconcile(c, Instant.now().minusSeconds(300));
            assertThat(reconciled).isZero();
            assertThat(activeCount(c, gateway)).isEqualTo(1);
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("REC3: already-released reservation is unchanged by reconciliation")
    void rec3_releasedReservationUnchanged() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-000000000116");
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);
            // Old AND already released: reconciliation must not touch it.
            UUID reservationId = insertResAtReturningId(c, gateway, TENANT,
                    Instant.now().minusSeconds(600));
            release(c, gateway);

            int reconciled = reconcile(c, Instant.now().minusSeconds(300));
            assertThat(reconciled).isZero();
            // released_at was populated by release, not reconciliation.
            assertThat(releasedAtOf(c, reservationId)).isNotNull();
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("RES1: reserve inserts active row with released_at NULL (DDL default)")
    void res1_reserveInsertsActiveRow() throws Exception {
        UUID gateway = UUID.fromString("ee000000-0000-4000-8000-000000000117");
        try (Connection c = rawConnection()) {
            insertGatewayRow(c, gateway);
            VoicePostgresIntegrationSupport.insertTenantRow(c, TENANT);

            // Exact reserve INSERT from VoiceCapacityServiceImpl.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO voice_channel_reservations (gateway_id, tenant_id) VALUES (?, ?)")) {
                ps.setObject(1, gateway);
                ps.setObject(2, TENANT);
                ps.executeUpdate();
            }

            assertThat(activeCount(c, gateway)).isEqualTo(1);
            // released_at defaulted to NULL — the row is active.
            assertThat(scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                    + "WHERE gateway_id = ? AND released_at IS NULL", gateway)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // SQL helpers (mirroring VoiceCapacityServiceImpl statements)
    // ------------------------------------------------------------------

    private void insertGatewayRow(Connection c, UUID gateway) throws Exception {
        VoicePostgresIntegrationSupport.insertGatewayRow(c, gateway, 10);
    }

    private void insertRes(Connection c, UUID gateway, UUID tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO voice_channel_reservations (gateway_id, tenant_id) VALUES (?, ?)")) {
            ps.setObject(1, gateway);
            ps.setObject(2, tenant);
            ps.executeUpdate();
        }
    }

    private UUID insertResAtReturningId(Connection c, UUID gateway, UUID tenant, Instant at) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO voice_channel_reservations (id, gateway_id, tenant_id, reserved_at) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, gateway);
            ps.setObject(3, tenant);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.executeUpdate();
        }
        return id;
    }

    private void insertResAt(Connection c, UUID gateway, UUID tenant, Instant at) throws Exception {
        insertResAtReturningId(c, gateway, tenant, at);
    }

    private int release(Connection c, UUID gateway) throws Exception {
        return releaseForTenant(c, gateway, TENANT);
    }

    private int releaseForTenant(Connection c, UUID gateway, UUID tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE voice_channel_reservations SET released_at = now() "
                        + "WHERE gateway_id = ? AND tenant_id = ? AND released_at IS NULL")) {
            ps.setObject(1, gateway);
            ps.setObject(2, tenant);
            return ps.executeUpdate();
        }
    }

    private int reconcile(Connection c, Instant cutoff) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE voice_channel_reservations SET released_at = now() "
                        + "WHERE released_at IS NULL AND reserved_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        }
    }

    private int activeCount(Connection c, UUID gateway) throws Exception {
        return scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                + "WHERE gateway_id = ? AND released_at IS NULL", gateway);
    }

    private int totalCount(Connection c, UUID gateway) throws Exception {
        return scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                + "WHERE gateway_id = ?", gateway);
    }

    private Instant releasedAtOf(Connection c, UUID reservationId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT released_at FROM voice_channel_reservations WHERE id = ?")) {
            ps.setObject(1, reservationId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            Timestamp ts = rs.getTimestamp(1);
            return ts == null ? null : ts.toInstant();
        }
    }
}
