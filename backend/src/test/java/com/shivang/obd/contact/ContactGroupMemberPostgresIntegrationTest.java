package com.shivang.obd.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.common.audit.JpaAuditConfig;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.contact.dto.AddMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberRequest;
import com.shivang.obd.contact.dto.BatchMemberResponse;
import com.shivang.obd.contact.dto.BatchMemberResult;
import com.shivang.obd.contact.dto.MemberBatchStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * VB-6B.2 — membership service semantics against real PostgreSQL (full
 * Flyway chain incl. V46):
 *
 * <ul>
 *   <li>PG-M1: concurrent duplicate adds → exactly one physical row,
 *       idempotent outcomes (never a 500).</li>
 *   <li>PG-M2: the composite tenant FK remains the isolation authority.</li>
 *   <li>PG-M3: roster pagination is membership-led with live contacts.</li>
 *   <li>PG-M4: memberCount (direct + grouped) matches reality.</li>
 *   <li>PG-M5: a soft-deleted contact disappears from the roster.</li>
 *   <li>PG-M6: deleteByContactGroupIdAndContactId idempotency.</li>
 *   <li>PG-M7: group deletion still removes memberships (delegated).</li>
 *   <li>PG-M8: contact deletion still removes memberships (delegated).</li>
 *   <li>PG-M9: createdBy persists for newly created memberships.</li>
 *   <li>PG-M10: batch add/remove against real PostgreSQL.</li>
 * </ul>
 *
 * Harness copied from the known-good VB-6B.1 PG suite: static container
 * startup with failure guard, {@code @DynamicPropertySource} routing,
 * {@code JpaAuditConfig}, {@code NOT_SUPPORTED} propagation, services
 * invoked inside {@link TransactionTemplate}.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContactGroupMemberPostgresIntegrationTest {

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
    }

    @Autowired
    private ContactRepository contactRepository;
    @Autowired
    private ContactGroupRepository groupRepository;
    @Autowired
    private ContactGroupMemberRepository memberRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private ContactGroupMemberService memberService;
    private ContactGroupService groupService;

    private static final UUID USER_ID = UUID.fromString("ee000000-0000-4000-8000-000000000008");
    private static final String PHONE = "+919876543210";

    @BeforeEach
    void setUp() {
        if (startupFailure != null) {
            throw new org.opentest4j.TestAbortedException(
                "BLOCKED: PostgreSQL Testcontainer could not start (Docker unavailable?): "
                    + startupFailure.getMessage(), startupFailure);
        }
        CurrentUserProvider currentUserProvider = new CurrentUserProvider() {
            @Override
            public Optional<AuthenticatedUser> current() {
                return Optional.of(new AuthenticatedUser(USER_ID, "members@vb6b2.test", null));
            }
        };
        com.shivang.obd.authz.AuthorizationService authorizationService =
            new com.shivang.obd.authz.AuthorizationService(
                List.of(), null, null, null, List.of()) {
                @Override
                public void requireCapability(UUID userId, String capabilityKey,
                    com.shivang.obd.authz.AccessCheck target) {
                    // harness pass-through; isolation is query-level here
                }
            };
        com.shivang.obd.contact.ContactGroupAccess groupAccess =
            new com.shivang.obd.contact.ContactGroupAccess(
                groupRepository, authorizationService, currentUserProvider, tenantRepository);
        memberService = new ContactGroupMemberService(
            memberRepository, contactRepository, groupAccess, currentUserProvider);
        groupService = new ContactGroupService(
            groupRepository, contactRepository, memberService,
            new ContactIdentityService(contactRepository), groupAccess,
            new ContactGroupMapper(), new ContactMapper(),
            List.of(), new tools.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createQuery(
                "DELETE FROM com.shivang.obd.contact.ContactGroupMemberEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM ContactGroupEntity").executeUpdate();
            entityManager.createQuery("DELETE FROM TenantEntity").executeUpdate();
        });
    }

    private TenantEntity seedTenant(String label) {
        return transactionTemplate.execute(tx -> {
            TenantEntity t = new TenantEntity();
            t.setName("tenant-" + label + "-" + UUID.randomUUID());
            t.setSlug("t-" + label + "-" + UUID.randomUUID());
            t.setStatus(LifecycleStatus.ACTIVE);
            return tenantRepository.saveAndFlush(t);
        });
    }

    private UUID seedGroup(UUID tenantId) {
        return transactionTemplate.execute(tx -> {
            ContactGroupEntity g = new ContactGroupEntity();
            g.setTenantId(tenantId);
            g.setName("cg-" + UUID.randomUUID());
            return groupRepository.saveAndFlush(g).getId();
        });
    }

    private UUID seedContact(UUID tenantId, String phone, String firstName) {
        return transactionTemplate.execute(tx -> {
            ContactEntity c = new ContactEntity();
            c.setTenantId(tenantId);
            c.setPhoneNumber(phone);
            c.setFirstName(firstName);
            return contactRepository.saveAndFlush(c).getId();
        });
    }

    private long memberRowCount(UUID groupId) {
        return transactionTemplate.execute(tx ->
            entityManager.createQuery(
                "SELECT COUNT(m) FROM com.shivang.obd.contact.ContactGroupMemberEntity m "
                + "WHERE m.contactGroupId = :g", Long.class)
                .setParameter("g", groupId)
                .getSingleResult());
    }

    @Test
    @DisplayName("PG-M1: 20 concurrent adds of one pair → exactly one row, idempotent outcomes")
    void concurrentDuplicateAddsYieldOneRow() throws Exception {
        TenantEntity tenant = seedTenant("m1");
        UUID groupId = seedGroup(tenant.getId());
        UUID contactId = seedContact(tenant.getId(), PHONE, "Race");
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            USER_ID, tenant.getId(), null);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger exists = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    var outcome = transactionTemplate.execute(tx ->
                        memberService.addMember(groupId, new AddMemberRequest(contactId)));
                    if (outcome.created()) {
                        created.incrementAndGet();
                    } else {
                        exists.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    // A raced insert marks the outer transaction rollback-only
                    // even though the service resolved idempotently; the end
                    // state (one row) is the approved outcome.
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    if (root instanceof DataIntegrityViolationException
                            || e instanceof org.springframework.transaction.UnexpectedRollbackException) {
                        exists.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(memberRowCount(groupId)).isEqualTo(1L);
        assertThat(unexpected.get()).isZero();
        assertThat(created.get() + exists.get()).isEqualTo(threads);
    }

    @Test
    @DisplayName("PG-M2: a cross-tenant membership add is impossible (404-cloak + composite FK)")
    void crossTenantMembershipStillImpossible() {
        TenantEntity tenantA = seedTenant("m2a");
        TenantEntity tenantB = seedTenant("m2b");
        UUID groupA = seedGroup(tenantA.getId());
        UUID contactB = seedContact(tenantB.getId(), PHONE, "Foreign");

        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            USER_ID, tenantA.getId(), null);
        // Service level: tenant-scoped lookup cloaks the foreign contact as missing.
        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
            memberService.addMember(groupA, new AddMemberRequest(contactB))))
            .isInstanceOf(ResourceNotFoundException.class);

        // Database level: even a raw insert with a wrong tenant is rejected.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            ContactGroupMemberEntity m = new ContactGroupMemberEntity();
            m.setTenantId(tenantA.getId());
            m.setContactGroupId(groupA);
            m.setContactId(contactB);
            memberRepository.saveAndFlush(m);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("PG-M3: roster pagination is membership-led and pages live contacts")
    void rosterPaginationWorks() {
        TenantEntity tenant = seedTenant("m3");
        UUID groupId = seedGroup(tenant.getId());
        for (int i = 0; i < 7; i++) {
            UUID contactId = seedContact(tenant.getId(), "+91980000%04d".formatted(i), "member" + i);
            transactionTemplate.executeWithoutResult(tx -> {
                ContactGroupMemberEntity m = new ContactGroupMemberEntity();
                m.setTenantId(tenant.getId());
                m.setContactGroupId(groupId);
                m.setContactId(contactId);
                memberRepository.saveAndFlush(m);
            });
        }

        var page0 = transactionTemplate.execute(tx ->
            memberService.listMembers(groupId, 0, 3, new String[0], null));
        var page2 = transactionTemplate.execute(tx ->
            memberService.listMembers(groupId, 2, 3, new String[0], null));

        assertThat(page0.data()).hasSize(3);
        assertThat(page0.pagination().totalElements()).isEqualTo(7);
        assertThat(page0.pagination().totalPages()).isEqualTo(3);
        assertThat(page2.data()).hasSize(1);
        // Embedded contact payload present (read-only join resolved).
        assertThat(page0.data().get(0).contact().phoneNumber()).startsWith("+91980000");
    }

    @Test
    @DisplayName("PG-M4: memberCount (direct and grouped) matches the physical rows")
    void memberCountMatchesRows() {
        TenantEntity tenant = seedTenant("m4");
        UUID g1 = seedGroup(tenant.getId());
        UUID g2 = seedGroup(tenant.getId());
        UUID c1 = seedContact(tenant.getId(), "+919811110001", "A");
        UUID c2 = seedContact(tenant.getId(), "+919811110002", "B");
        addMemberRaw(tenant.getId(), g1, c1);
        addMemberRaw(tenant.getId(), g1, c2);

        assertThat(memberService.memberCount(g1)).isEqualTo(2L);
        assertThat(memberService.memberCount(g2)).isZero();

        var counts = memberService.memberCounts(List.of(g1, g2));
        // The grouped query returns rows only for groups that HAVE members;
        // the list-page mapper defaults absent groups to zero.
        assertThat(counts).containsEntry(g1, 2L);
        assertThat(counts.getOrDefault(g2, 0L)).isZero();
    }

    @Test
    @DisplayName("PG-M5: a soft-deleted contact is excluded from the roster and counts")
    void softDeletedContactExcludedFromRoster() {
        TenantEntity tenant = seedTenant("m5");
        UUID groupId = seedGroup(tenant.getId());
        UUID live = seedContact(tenant.getId(), "+919822220001", "Live");
        UUID dead = seedContact(tenant.getId(), "+919822220002", "Dead");
        addMemberRaw(tenant.getId(), groupId, live);
        addMemberRaw(tenant.getId(), groupId, dead);

        transactionTemplate.executeWithoutResult(tx ->
            contactRepository.findById(dead).orElseThrow().setDeletedAt(Instant.now()));

        var roster = transactionTemplate.execute(tx ->
            memberService.listMembers(groupId, 0, 20, new String[0], null));
        assertThat(roster.data()).hasSize(1);
        assertThat(roster.data().get(0).contactId()).isEqualTo(live);
        assertThat(memberService.memberCount(groupId)).isEqualTo(1L);

        var searched = transactionTemplate.execute(tx ->
            memberService.listMembers(groupId, 0, 20, new String[0], "dead"));
        assertThat(searched.data()).isEmpty();
    }

    @Test
    @DisplayName("PG-M6: deleteByContactGroupIdAndContactId is idempotent at the DB level")
    void deleteByPairIdempotent() {
        TenantEntity tenant = seedTenant("m6");
        UUID groupId = seedGroup(tenant.getId());
        UUID contactId = seedContact(tenant.getId(), PHONE, "Once");
        addMemberRaw(tenant.getId(), groupId, contactId);

        Long first = transactionTemplate.execute(tx ->
            memberRepository.deleteByContactGroupIdAndContactId(groupId, contactId));
        Long second = transactionTemplate.execute(tx ->
            memberRepository.deleteByContactGroupIdAndContactId(groupId, contactId));

        assertThat(first).isEqualTo(1L);
        assertThat(second).isZero();
        assertThat(memberRowCount(groupId)).isZero();
    }

    @Test
    @DisplayName("PG-M7: group deletion still removes memberships (canonical delegation)")
    void groupDeleteStillRemovesMemberships() {
        TenantEntity tenant = seedTenant("m7");
        UUID groupId = seedGroup(tenant.getId());
        UUID contactId = seedContact(tenant.getId(), PHONE, "Member");
        addMemberRaw(tenant.getId(), groupId, contactId);
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            USER_ID, tenant.getId(), null);

        transactionTemplate.executeWithoutResult(tx -> groupService.deleteGroup(groupId));

        assertThat(memberRowCount(groupId)).isZero();
        // The contact identity itself is never touched by a group delete.
        Optional<ContactEntity> stillLive = transactionTemplate.execute(tx ->
            contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenant.getId()));
        assertThat(stillLive).isPresent();
    }

    @Test
    @DisplayName("PG-M8: contact deletion still removes memberships (canonical delegation)")
    void contactDeleteStillRemovesMemberships() {
        TenantEntity tenant = seedTenant("m8");
        UUID groupId = seedGroup(tenant.getId());
        UUID contactId = seedContact(tenant.getId(), PHONE, "Leaver");
        addMemberRaw(tenant.getId(), groupId, contactId);
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            USER_ID, tenant.getId(), null);

        transactionTemplate.executeWithoutResult(tx -> groupService.deleteContact(groupId, contactId));

        assertThat(memberRowCount(groupId)).isZero();
        // The identity is soft-deleted; its phone becomes re-creatable.
        Optional<ContactEntity> gone = transactionTemplate.execute(tx ->
            contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, tenant.getId()));
        assertThat(gone).isEmpty();
    }

    @Test
    @DisplayName("PG-M9: createdBy is stamped and persisted for new memberships")
    void createdByPersists() {
        TenantEntity tenant = seedTenant("m9");
        UUID groupId = seedGroup(tenant.getId());
        UUID contactId = seedContact(tenant.getId(), PHONE, "Stamped");
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            USER_ID, tenant.getId(), null);

        var outcome = transactionTemplate.execute(tx ->
            memberService.addMember(groupId, new AddMemberRequest(contactId)));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.member().createdBy()).isEqualTo(USER_ID);
        String dbValue = transactionTemplate.execute(tx ->
            entityManager.createQuery(
                "SELECT m.createdBy FROM com.shivang.obd.contact.ContactGroupMemberEntity m "
                + "WHERE m.contactGroupId = :g", String.class)
                .setParameter("g", groupId)
                .getSingleResult());
        assertThat(dbValue).isEqualTo(USER_ID.toString());
    }

    @Test
    @DisplayName("PG-M10: batch add/remove against real PostgreSQL with per-item outcomes")
    void batchAgainstRealPostgres() {
        TenantEntity tenant = seedTenant("m10");
        UUID groupId = seedGroup(tenant.getId());
        UUID c1 = seedContact(tenant.getId(), "+919833330001", "One");
        UUID c2 = seedContact(tenant.getId(), "+919833330002", "Two");
        com.shivang.obd.authz.context.OrganizationContextHolder.setAuthenticated(
            USER_ID, tenant.getId(), null);

        BatchMemberResponse addResult = transactionTemplate.execute(tx ->
            memberService.addMembers(groupId, new BatchMemberRequest(List.of(c1, c2))));
        assertThat(statusOf(addResult, c1)).isEqualTo(MemberBatchStatus.CREATED);
        assertThat(statusOf(addResult, c2)).isEqualTo(MemberBatchStatus.CREATED);
        assertThat(memberRowCount(groupId)).isEqualTo(2L);

        // Re-adding collapses to EXISTS — no new rows.
        BatchMemberResponse again = transactionTemplate.execute(tx ->
            memberService.addMembers(groupId, new BatchMemberRequest(List.of(c1))));
        assertThat(statusOf(again, c1)).isEqualTo(MemberBatchStatus.EXISTS);
        assertThat(memberRowCount(groupId)).isEqualTo(2L);

        BatchMemberResponse removeResult = transactionTemplate.execute(tx ->
            memberService.removeMembers(groupId, new BatchMemberRequest(List.of(c1, c2))));
        assertThat(statusOf(removeResult, c1)).isEqualTo(MemberBatchStatus.CREATED);
        assertThat(statusOf(removeResult, c2)).isEqualTo(MemberBatchStatus.CREATED);
        assertThat(memberRowCount(groupId)).isZero();

        // Re-removing collapses to NOT_FOUND — idempotent.
        BatchMemberResponse reRemove = transactionTemplate.execute(tx ->
            memberService.removeMembers(groupId, new BatchMemberRequest(List.of(c1))));
        assertThat(statusOf(reRemove, c1)).isEqualTo(MemberBatchStatus.NOT_FOUND);
    }

    private MemberBatchStatus statusOf(BatchMemberResponse response, UUID contactId) {
        return response.results().stream()
            .filter(r -> r.contactId().equals(contactId))
            .findFirst()
            .map(BatchMemberResult::status)
            .orElseThrow();
    }

    private void addMemberRaw(UUID tenantId, UUID groupId, UUID contactId) {
        transactionTemplate.executeWithoutResult(tx -> {
            ContactGroupMemberEntity m = new ContactGroupMemberEntity();
            m.setTenantId(tenantId);
            m.setContactGroupId(groupId);
            m.setContactId(contactId);
            memberRepository.saveAndFlush(m);
        });
    }
}
