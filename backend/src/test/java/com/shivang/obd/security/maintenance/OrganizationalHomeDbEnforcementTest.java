package com.shivang.obd.security.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves that PostgreSQL triggers enforce the organizational-home ↔
 * membership type-consistency invariant at the DATABASE level.
 * Requires dev PostgreSQL to be running.
 */
class OrganizationalHomeDbEnforcementTest {

    private static final UUID ROLE_TENANT_ADMIN =
        UUID.fromString("501e0000-0000-4000-8000-000000000003");
    private static final UUID ROLE_RESELLER_ADMIN =
        UUID.fromString("501e0000-0000-4000-8000-000000000002");

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/obd", "obd_user", "obd_password");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM tenant_memberships WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%dbenft%')");
            stmt.execute("DELETE FROM reseller_memberships WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%dbenft%')");
            stmt.execute("DELETE FROM organizational_homes WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%dbenft%')");
            stmt.execute("DELETE FROM tenants WHERE slug LIKE '%dbenft%'");
            stmt.execute("DELETE FROM resellers WHERE slug LIKE '%dbenft%'");
            stmt.execute("DELETE FROM users WHERE email LIKE '%dbenft%'");
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private UUID insertUser() throws Exception {
        var id = UUID.randomUUID();
        var s = UUID.randomUUID().toString().substring(0, 8);
        try (var ps = connection.prepareStatement(
            "INSERT INTO users (id, email, normalized_email, display_name) VALUES (?, ?, ?, 'DB Test')")) {
            ps.setObject(1, id); ps.setString(2, "dbenft+" + s + "@obd.test"); ps.setString(3, "dbenft+" + s + "@obd.test");
            ps.executeUpdate();
        }
        return id;
    }

    private UUID insertTenant() throws Exception {
        var id = UUID.randomUUID();
        var s = UUID.randomUUID().toString().substring(0, 8);
        try (var ps = connection.prepareStatement("INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)")) {
            ps.setObject(1, id); ps.setString(2, "DB Test T " + s); ps.setString(3, "dbenft-t-" + s);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID insertReseller() throws Exception {
        var id = UUID.randomUUID();
        var s = UUID.randomUUID().toString().substring(0, 8);
        try (var ps = connection.prepareStatement("INSERT INTO resellers (id, name, slug) VALUES (?, ?, ?)")) {
            ps.setObject(1, id); ps.setString(2, "DB Test R " + s); ps.setString(3, "dbenft-r-" + s);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertHome(UUID id, UUID userId, String type, UUID orgId) throws Exception {
        try (var ps = connection.prepareStatement(
            "INSERT INTO organizational_homes (id, user_id, home_type, organization_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id); ps.setObject(2, userId); ps.setString(3, type); ps.setObject(4, orgId);
            ps.executeUpdate();
        }
    }

    private void insertTM(UUID id, UUID userId, UUID homeId, UUID roleId, UUID tenantId) throws Exception {
        try (var ps = connection.prepareStatement(
            "INSERT INTO tenant_memberships (id, user_id, organizational_home_id, role_id, tenant_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id); ps.setObject(2, userId); ps.setObject(3, homeId);
            ps.setObject(4, roleId); ps.setObject(5, tenantId);
            ps.executeUpdate();
        }
    }

    private void insertRM(UUID id, UUID userId, UUID homeId, UUID roleId, UUID resellerId) throws Exception {
        try (var ps = connection.prepareStatement(
            "INSERT INTO reseller_memberships (id, user_id, organizational_home_id, role_id, reseller_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id); ps.setObject(2, userId); ps.setObject(3, homeId);
            ps.setObject(4, roleId); ps.setObject(5, resellerId);
            ps.executeUpdate();
        }
    }

    // A
    @Test
    void validTenantHomePlusTenantMembershipSucceeds() throws Exception {
        var u = insertUser(); var t = insertTenant(); var h = UUID.randomUUID();
        insertHome(h, u, "TENANT", t);

        assertThatCode(() -> insertTM(UUID.randomUUID(), u, h, ROLE_TENANT_ADMIN, t)).doesNotThrowAnyException();
    }

    // B
    @Test
    void validResellerHomePlusResellerMembershipSucceeds() throws Exception {
        var u = insertUser(); var r = insertReseller(); var h = UUID.randomUUID();
        insertHome(h, u, "RESELLER", r);

        assertThatCode(() -> insertRM(UUID.randomUUID(), u, h, ROLE_RESELLER_ADMIN, r)).doesNotThrowAnyException();
    }

    // C: TENANT home + RESELLER membership → rejected by DB trigger
    @Test
    void resellerMembershipReferencingTenantHomeRejectedByDb() throws Exception {
        var u = insertUser(); var t = insertTenant(); var h = UUID.randomUUID(); var r = insertReseller();
        insertHome(h, u, "TENANT", t);

        var throwable = catchThrowable(() ->
            insertRM(UUID.randomUUID(), u, h, ROLE_RESELLER_ADMIN, r));

        assertThat(throwable).isNotNull();
        assertThat(throwable.getMessage()).contains("not of type RESELLER");
    }

    // D: RESELLER home + TENANT membership → rejected by DB trigger
    @Test
    void tenantMembershipReferencingResellerHomeRejectedByDb() throws Exception {
        var u = insertUser(); var r = insertReseller(); var h = UUID.randomUUID(); var t = insertTenant();
        insertHome(h, u, "RESELLER", r);

        var throwable = catchThrowable(() ->
            insertTM(UUID.randomUUID(), u, h, ROLE_TENANT_ADMIN, t));

        assertThat(throwable).isNotNull();
        assertThat(throwable.getMessage()).contains("not of type TENANT");
    }

    // E
    @Test
    void repointingTenantMembershipToResellerHomeRejectedByDb() throws Exception {
        var u = insertUser(); var tA = insertTenant(); var tHid = UUID.randomUUID();
        insertHome(tHid, u, "TENANT", tA);
        var mId = UUID.randomUUID();
        insertTM(mId, u, tHid, ROLE_TENANT_ADMIN, tA);
        var otherUser = insertUser();
        var r = insertReseller(); var rHid = UUID.randomUUID();
        insertHome(rHid, otherUser, "RESELLER", r);

        var throwable = catchThrowable(() -> {
            try (var ps = connection.prepareStatement(
                "UPDATE tenant_memberships SET organizational_home_id = ? WHERE id = ?")) {
                ps.setObject(1, rHid); ps.setObject(2, mId); ps.executeUpdate();
            }
        });
        assertThat(throwable).isNotNull();
        assertThat(throwable.getMessage()).contains("not of type TENANT");

        try (var ps = connection.prepareStatement(
            "SELECT count(*) FROM tenant_memberships WHERE id = ? AND organizational_home_id = ?")) {
            ps.setObject(1, mId); ps.setObject(2, tHid);
            var rs = ps.executeQuery(); rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // F
    @Test
    void repointingResellerMembershipToTenantHomeRejectedByDb() throws Exception {
        var u = insertUser(); var r = insertReseller(); var rHid = UUID.randomUUID();
        insertHome(rHid, u, "RESELLER", r);
        var mId = UUID.randomUUID();
        insertRM(mId, u, rHid, ROLE_RESELLER_ADMIN, r);
        var otherUser = insertUser();
        var t = insertTenant(); var tHid = UUID.randomUUID();
        insertHome(tHid, otherUser, "TENANT", t);

        var throwable = catchThrowable(() -> {
            try (var ps = connection.prepareStatement(
                "UPDATE reseller_memberships SET organizational_home_id = ? WHERE id = ?")) {
                ps.setObject(1, tHid); ps.setObject(2, mId); ps.executeUpdate();
            }
        });
        assertThat(throwable).isNotNull();
        assertThat(throwable.getMessage()).contains("not of type RESELLER");
    }

    // G
    @Test
    void changingTenantHomeTypeToResellerRejectedByDb() throws Exception {
        var u = insertUser(); var t = insertTenant(); var h = UUID.randomUUID();
        insertHome(h, u, "TENANT", t);
        insertTM(UUID.randomUUID(), u, h, ROLE_TENANT_ADMIN, t);

        var throwable = catchThrowable(() -> {
            try (var ps = connection.prepareStatement(
                "UPDATE organizational_homes SET home_type = 'RESELLER' WHERE id = ?")) {
                ps.setObject(1, h); ps.executeUpdate();
            }
        });
        assertThat(throwable).isNotNull();
        assertThat(throwable.getMessage()).contains("TENANT to RESELLER");

        try (var ps = connection.prepareStatement(
            "SELECT home_type FROM organizational_homes WHERE id = ?")) {
            ps.setObject(1, h); var rs = ps.executeQuery(); rs.next();
            assertThat(rs.getString(1)).isEqualTo("TENANT");
        }
    }

    // H
    @Test
    void changingResellerHomeTypeToTenantRejectedByDb() throws Exception {
        var u = insertUser(); var r = insertReseller(); var h = UUID.randomUUID();
        insertHome(h, u, "RESELLER", r);
        insertRM(UUID.randomUUID(), u, h, ROLE_RESELLER_ADMIN, r);

        var throwable = catchThrowable(() -> {
            try (var ps = connection.prepareStatement(
                "UPDATE organizational_homes SET home_type = 'TENANT' WHERE id = ?")) {
                ps.setObject(1, h); ps.executeUpdate();
            }
        });
        assertThat(throwable).isNotNull();
        assertThat(throwable.getMessage()).contains("RESELLER to TENANT");

        try (var ps = connection.prepareStatement(
            "SELECT home_type FROM organizational_homes WHERE id = ?")) {
            ps.setObject(1, h); var rs = ps.executeQuery(); rs.next();
            assertThat(rs.getString(1)).isEqualTo("RESELLER");
        }
    }

    // K
    @Test
    void twoMembershipsOnSameHomeRejectedByUniqueIndex() throws Exception {
        var u = insertUser(); var t = insertTenant(); var h = UUID.randomUUID();
        insertHome(h, u, "TENANT", t);
        insertTM(UUID.randomUUID(), u, h, ROLE_TENANT_ADMIN, t);

        var throwable = catchThrowable(() ->
            insertTM(UUID.randomUUID(), u, h, ROLE_TENANT_ADMIN, t));
        assertThat(throwable).isNotNull();
    }
}
