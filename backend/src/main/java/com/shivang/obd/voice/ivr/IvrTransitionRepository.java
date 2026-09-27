package com.shivang.obd.voice.ivr;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to IVR transitions, always scoped by tree.
 * <p>
 * There is no find-by-id-only finder on purpose. Every read is tree-scoped, so a
 * transition can only ever be reached in the context of the tree that owns it,
 * which is the same isolation discipline the node and tree repositories follow.
 */
@Repository
public interface IvrTransitionRepository extends JpaRepository<IvrTransition, UUID> {

    List<IvrTransition> findByTreeIdAndDeletedAtIsNullOrderByIdAsc(UUID treeId);

    List<IvrTransition> findByTreeIdAndNodeIdInAndDeletedAtIsNull(
            UUID treeId, Collection<UUID> nodeIds);

    List<IvrTransition> findByTreeIdAndNodeIdAndDeletedAtIsNull(UUID treeId, UUID nodeId);

    void deleteByTreeIdAndDeletedAtIsNull(UUID treeId);
}
