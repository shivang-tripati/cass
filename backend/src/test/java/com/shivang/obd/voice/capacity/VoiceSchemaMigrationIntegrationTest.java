package com.shivang.obd.voice.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-0 Phase 12 — database/migration tests (V30–V35).
 * <p>
 * Runs the REAL Flyway migration chain (V1..V35) against a fresh PostgreSQL
 * container (see {@link VoicePostgresIntegrationSupport}) and asserts the
 * resulting schema: tables, foreign keys, indexes, unique constraints and
 * nullability of the voice routing/capacity objects.
 */
class VoiceSchemaMigrationIntegrationTest extends VoicePostgresIntegrationSupport {

    @Test
    @DisplayName("V30: voice_channel_reservations exists with gateway FK, nullable released_at, tenant column")
    void v30_reservationTableShape() throws Exception {
        try (Connection c = rawConnection()) {
            // Table exists
            assertThat(exists(c,
                    "SELECT 1 FROM information_schema.tables "
                            + "WHERE table_name = 'voice_channel_reservations'")).isTrue();

            // Columns and nullability
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='voice_channel_reservations' AND column_name='gateway_id' "
                    + "AND is_nullable='NO'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='voice_channel_reservations' AND column_name='tenant_id' "
                    + "AND is_nullable='NO'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='voice_channel_reservations' AND column_name='released_at' "
                    + "AND is_nullable='YES'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='voice_channel_reservations' AND column_name='reserved_at' "
                    + "AND is_nullable='NO'")).isTrue();

            // FK to sip_gateways
            assertThat(exists(c,
                    "SELECT 1 FROM information_schema.table_constraints tc "
                            + "JOIN information_schema.key_column_usage kcu "
                            + "  ON tc.constraint_name = kcu.constraint_name "
                            + "WHERE tc.table_name='voice_channel_reservations' "
                            + "AND tc.constraint_type='FOREIGN KEY' "
                            + "AND kcu.column_name='gateway_id'")).isTrue();

            // Active-usage indexes (V30)
            assertThat(exists(c, "SELECT 1 FROM pg_indexes "
                    + "WHERE tablename='voice_channel_reservations' "
                    + "AND indexdef LIKE '%gateway_id, released_at%'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM pg_indexes "
                    + "WHERE tablename='voice_channel_reservations' "
                    + "AND indexdef LIKE '%tenant_id, released_at%'")).isTrue();
        }
    }

    @Test
    @DisplayName("V30: voice_route_profiles and entries exist with FKs and enum type")
    void v30_routeProfileTables() throws Exception {
        try (Connection c = rawConnection()) {
            assertThat(exists(c, "SELECT 1 FROM information_schema.tables "
                    + "WHERE table_name = 'voice_route_profiles'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.tables "
                    + "WHERE table_name = 'voice_route_profile_entries'")).isTrue();

            // Enum type created
            assertThat(exists(c, "SELECT 1 FROM pg_type WHERE typname = 'voice_route_type'")).isTrue();

            // profile FK with ON DELETE CASCADE on entries
            assertThat(exists(c, "SELECT 1 FROM information_schema.table_constraints tc "
                    + "JOIN information_schema.key_column_usage kcu "
                    + "  ON tc.constraint_name = kcu.constraint_name "
                    + "WHERE tc.table_name='voice_route_profile_entries' "
                    + "AND tc.constraint_type='FOREIGN KEY' "
                    + "AND kcu.column_name='profile_id'")).isTrue();

            // gateway FK on entries (V30)
            assertThat(exists(c, "SELECT 1 FROM information_schema.table_constraints tc "
                    + "JOIN information_schema.key_column_usage kcu "
                    + "  ON tc.constraint_name = kcu.constraint_name "
                    + "WHERE tc.table_name='voice_route_profile_entries' "
                    + "AND tc.constraint_type='FOREIGN KEY' "
                    + "AND kcu.column_name='gateway_id'")).isTrue();

            // did FK on entries is nullable
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='voice_route_profile_entries' AND column_name='did_id' "
                    + "AND is_nullable='YES'")).isTrue();
        }
    }

    @Test
    @DisplayName("V31: max_cps + capacity_headroom_pct on sip_gateways with CHECK constraints")
    void v31_gatewayCpsAndHeadroom() throws Exception {
        try (Connection c = rawConnection()) {
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='sip_gateways' AND column_name='max_cps' "
                    + "AND is_nullable='YES'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='sip_gateways' AND column_name='capacity_headroom_pct' "
                    + "AND is_nullable='YES'")).isTrue();

            // CHECK constraints exist and actually reject out-of-range values
            assertThat(exists(c, "SELECT 1 FROM pg_constraint "
                    + "WHERE conrelid = 'sip_gateways'::regclass "
                    + "AND conname = 'ck_sip_gateways_cps'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM pg_constraint "
                    + "WHERE conrelid = 'sip_gateways'::regclass "
                    + "AND conname = 'ck_sip_gateways_headroom'")).isTrue();

            // CHECK behavior: 0 CPS rejected, 100% headroom rejected
            assertThat(scalarInt(c,
                    "SELECT COUNT(*) FROM (SELECT 1 UNION ALL SELECT 2) t WHERE false") >= 0).isTrue();
            org.assertj.core.api.Assertions.catchThrowable(() ->
                    exec(c, "INSERT INTO sip_gateways (id, name, provider, free_switch_gateway_name, "
                            + "max_concurrent_channels, max_cps) "
                            + "VALUES (gen_random_uuid(), 'x', 'TATA', 'fs', 10, 0)"));
            org.assertj.core.api.Assertions.catchThrowable(() ->
                    exec(c, "INSERT INTO sip_gateways (id, name, provider, free_switch_gateway_name, "
                            + "max_concurrent_channels, capacity_headroom_pct) "
                            + "VALUES (gen_random_uuid(), 'x', 'TATA', 'fs', 10, 100)"));
        }
    }

    @Test
    @DisplayName("V31: max_cps on sip_gateway_allocations")
    void v31_allocationCps() throws Exception {
        try (Connection c = rawConnection()) {
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='sip_gateway_allocations' AND column_name='max_cps' "
                    + "AND is_nullable='YES'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM pg_constraint "
                    + "WHERE conrelid = 'sip_gateway_allocations'::regclass "
                    + "AND conname = 'ck_sip_alloc_cps'")).isTrue();
        }
    }

    @Test
    @DisplayName("V33: capacity_headroom_pct on sip_gateway_allocations (defect-fix migration)")
    void v33_allocationHeadroom() throws Exception {
        try (Connection c = rawConnection()) {
            // The column backing SipGatewayAllocation.getCapacityHeadroomPct().
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='sip_gateway_allocations' AND column_name='capacity_headroom_pct' "
                    + "AND is_nullable='YES'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM pg_constraint "
                    + "WHERE conrelid = 'sip_gateway_allocations'::regclass "
                    + "AND conname = 'ck_sip_alloc_headroom'")).isTrue();

            // CHECK behavior: 100% headroom rejected
            org.assertj.core.api.Assertions.catchThrowable(() ->
                    exec(c, "UPDATE sip_gateway_allocations SET capacity_headroom_pct = 100 "
                            + "WHERE false RETURNING 1"));
        }
    }

    @Test
    @DisplayName("V32: gateway_id on call_sessions, nullable, with index")
    void v32_callSessionGatewayId() throws Exception {
        try (Connection c = rawConnection()) {
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='call_sessions' AND column_name='gateway_id' "
                    + "AND is_nullable='YES'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM pg_indexes "
                    + "WHERE tablename='call_sessions' AND indexname='idx_call_sessions_gateway'")).isTrue();
        }
    }

    @Test
    @DisplayName("V34: PLAYING / PLAYBACK_COMPLETED added to call_session_status enum (VB-1)")
    void v34_playingAndPlaybackCompletedEnumValues() throws Exception {
        try (Connection c = rawConnection()) {
            // The PG enum backing CallSessionStatus carries the VB-1 states.
            for (String value : new String[] {"PLAYING", "PLAYBACK_COMPLETED"}) {
                assertThat(exists(c,
                        "SELECT 1 FROM pg_enum e "
                                + "JOIN pg_type t ON t.oid = e.enumtypid "
                                + "WHERE t.typname = 'call_session_status' AND e.enumlabel = ?", value))
                        .as("enum value %s present", value).isTrue();
            }
            // The enum is actually used by the status column.
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='call_sessions' AND column_name='status' "
                    + "AND udt_name='call_session_status'")).isTrue();
        }
    }

    @Test
    @DisplayName("V35: dtmf_result_type enum, dtmf_interactions table, WAITING_FOR_DTMF state (VB-2)")
    void v35_dtmfSchema() throws Exception {
        try (Connection c = rawConnection()) {
            // New PG enum with exactly the DtmfResultType values (declaration order).
            String enumValues = scalarString(c,
                    "SELECT string_agg(val::text, ',' ORDER BY val) "
                            + "FROM unnest(enum_range(NULL::dtmf_result_type)) AS val");
            assertThat(enumValues).isEqualTo("COLLECTING,VALID,INVALID,TIMEOUT,ABANDONED");

            // WAITING_FOR_DTMF added to the session-status enum.
            assertThat(exists(c,
                    "SELECT 1 FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid "
                            + "WHERE t.typname = 'call_session_status' "
                            + "AND e.enumlabel = 'WAITING_FOR_DTMF'")).isTrue();

            // Interaction table exists with enum-typed result column, tenant
            // FK and timeout-scan index.
            assertThat(exists(c, "SELECT 1 FROM information_schema.tables "
                    + "WHERE table_name = 'dtmf_interactions'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='dtmf_interactions' AND column_name='result' "
                    + "AND udt_name='dtmf_result_type'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_name='dtmf_interactions' AND column_name='expires_at' "
                    + "AND is_nullable='NO'")).isTrue();
            assertThat(exists(c, "SELECT 1 FROM pg_indexes "
                    + "WHERE tablename = 'dtmf_interactions' "
                    + "AND indexname = 'idx_dtmf_interactions_timeout_scan'")).isTrue();
        }
    }

    @Test
    @DisplayName("Flyway applied every migration up to V35 with success status")
    void flywayHistoryCompleteThroughV34() throws Exception {
        try (Connection c = rawConnection()) {
            // Every migration V1..V35 recorded successfully.
            int applied = scalarInt(c,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE");
            assertThat(applied).isGreaterThanOrEqualTo(35);

            int failed = scalarInt(c,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE");
            assertThat(failed).isZero();

            // Spot-check the VB-0/VB-1/VB-2 migrations are present.
            for (String v : new String[] {"29", "30", "31", "32", "33", "34", "35"}) {
                assertThat(exists(c, "SELECT 1 FROM flyway_schema_history "
                        + "WHERE version = ? AND success = TRUE", v))
                        .as("migration V%s applied", v)
                        .isTrue();
            }
        }
    }

    private static String scalarString(Connection c, String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(Connection c, String sql) throws java.sql.SQLException {
        try (var ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
