package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.voice.routing.VoiceRouteProfile;
import com.shivang.obd.voice.routing.VoiceRouteProfileRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-0 Phase 9 — tenant isolation at the persistence/service layer, backed by
 * real PostgreSQL.
 * <p>
 * Verifies that the ownership-scoped repository queries enforce tenant
 * boundaries at the data layer: Tenant A cannot read Tenant B's routing
 * profile; reservation usage counts are tenant-scoped; reservation release is
 * tenant-scoped (A cannot release B's capacity).
 */
class VoiceTenantIsolationIntegrationTest extends VoicePostgresIntegrationSupport {

    private static final UUID TENANT_A = UUID.fromString("dd000000-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("dd000000-0000-4000-8000-00000000000b");
    private static final UUID PROFILE_A = UUID.fromString("dd000000-0000-4000-8000-00000000001a");
    private static final UUID PROFILE_B = UUID.fromString("dd000000-0000-4000-8000-00000000001b");
    private static final UUID GATEWAY = UUID.fromString("dd000000-0000-4000-8000-000000000002");

    @Autowired
    VoiceRouteProfileRepository profileRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("repository lookup scoped by tenantId does not return another tenant's profile")
    void tenantACannotReadTenantBProfile() {
        // Two tenants, two profiles.
        insertTenant(TENANT_A, "iso-tenant-a");
        insertTenant(TENANT_B, "iso-tenant-b");
        insertProfile(PROFILE_A, TENANT_A, "profile-a");
        insertProfile(PROFILE_B, TENANT_B, "profile-b");

        // Ownership-scoped query: A cannot fetch B's profile by its id.
        Optional<VoiceRouteProfile> crossTenant =
                profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(PROFILE_B, TENANT_A);
        assertThat(crossTenant).isEmpty();

        // Own profile is returned.
        Optional<VoiceRouteProfile> ownProfile =
                profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(PROFILE_A, TENANT_A);
        assertThat(ownProfile).isPresent();

        // Tenant-scoped listing only shows own profiles.
        assertThat(profileRepository.findByTenantIdAndDeletedAtIsNull(TENANT_A))
                .extracting(VoiceRouteProfile::getId)
                .containsExactly(PROFILE_A);

        // Default-profile lookup is also tenant-scoped.
        assertThat(profileRepository.findFirstByTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(TENANT_B))
                .isPresent()
                .get()
                .extracting(VoiceRouteProfile::getId)
                .isEqualTo(PROFILE_B);
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("capacity usage counting is tenant-scoped in SQL")
    void capacityUsageCountingIsTenantScoped() throws Exception {
        insertTenant(TENANT_A, "iso-tenant-a2");
        insertTenant(TENANT_B, "iso-tenant-b2");

        UUID gateway = UUID.fromString("dd000000-0000-4000-8000-0000000000f1");
        try (Connection c = rawConnection()) {
            // Gateway FK row (fresh id for this test).
            VoicePostgresIntegrationSupport.insertGatewayRow(c, gateway, 10);
            // B holds 2 active reservations, A holds 1, on the shared gateway.
            insertRes(c, gateway, TENANT_B);
            insertRes(c, gateway, TENANT_B);
            insertRes(c, gateway, TENANT_A);

            // Gateway-wide count sees all 3 (this is the gateway-limit check).
            assertThat(scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                    + "WHERE gateway_id = ? AND released_at IS NULL", gateway)).isEqualTo(3);

            // Tenant-filtered count (the allocation-limit check) is scoped: B=2, A=1.
            assertThat(scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                    + "WHERE gateway_id = ? AND tenant_id = ? AND released_at IS NULL",
                    gateway, TENANT_B)).isEqualTo(2);
            assertThat(scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                    + "WHERE gateway_id = ? AND tenant_id = ? AND released_at IS NULL",
                    gateway, TENANT_A)).isEqualTo(1);
        }
    }

    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @DisplayName("tenant A releasing its own reservations does not touch tenant B's")
    void releaseIsTenantScoped() throws Exception {
        insertTenant(TENANT_A, "iso-tenant-a3");
        insertTenant(TENANT_B, "iso-tenant-b3");

        UUID gateway = UUID.fromString("dd000000-0000-4000-8000-0000000000f2");
        try (Connection c = rawConnection()) {
            VoicePostgresIntegrationSupport.insertGatewayRow(c, gateway, 10);
            // B has an active reservation, A has one too.
            insertRes(c, gateway, TENANT_A);
            insertRes(c, gateway, TENANT_B);

            // A releases ITS reservations only (the exact release SQL).
            int released;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE voice_channel_reservations SET released_at = now() "
                            + "WHERE gateway_id = ? AND tenant_id = ? AND released_at IS NULL")) {
                ps.setObject(1, gateway);
                ps.setObject(2, TENANT_A);
                released = ps.executeUpdate();
            }

            assertThat(released).isEqualTo(1);
            // B's reservation is untouched.
            assertThat(scalarInt(c, "SELECT COUNT(*) FROM voice_channel_reservations "
                    + "WHERE gateway_id = ? AND tenant_id = ? AND released_at IS NULL",
                    gateway, TENANT_B)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures via JdbcTemplate (rolled back with the REQUIRES_NEW tx)
    // ------------------------------------------------------------------

    private void insertTenant(UUID id, String slug) {
        // Same connection semantics as rawConnection: committed rows so the
        // reservations' tenant FK is satisfied. ON CONFLICT keeps reruns safe.
        try (var c = rawConnection()) {
            VoicePostgresIntegrationSupport.insertTenantRow(c, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertProfile(UUID id, UUID tenantId, String name) {
        jdbcTemplate.update(
                "INSERT INTO voice_route_profiles (id, tenant_id, name) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                id, tenantId, name);
    }

    private void insertRes(Connection c, UUID gateway, UUID tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO voice_channel_reservations (gateway_id, tenant_id) VALUES (?, ?)")) {
            ps.setObject(1, gateway);
            ps.setObject(2, tenant);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (var ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
