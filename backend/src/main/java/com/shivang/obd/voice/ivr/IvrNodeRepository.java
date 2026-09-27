package com.shivang.obd.voice.ivr;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped access to IVR nodes.
 * <p>
 * A node is always loaded through its tree, so callers pass the tenant rather
 * than a node id alone: that makes "same tenant" a query precondition instead of
 * a check somebody can forget.
 */
@Repository
public interface IvrNodeRepository extends JpaRepository<IvrNode, UUID> {

    /** The tree's live nodes, in stable key order so snapshots are deterministic. */
    List<IvrNode> findByTreeIdAndDeletedAtIsNullOrderByNodeKeyAsc(UUID treeId);

    List<IvrNode> findByTreeIdAndNodeKeyAndDeletedAtIsNull(UUID treeId, String nodeKey);

    List<IvrNode> findByTreeIdAndNodeKeyInAndDeletedAtIsNull(
            UUID treeId, Collection<String> nodeKeys);

    Optional<IvrNode> findByIdAndTreeIdAndDeletedAtIsNull(UUID id, UUID treeId);
}
