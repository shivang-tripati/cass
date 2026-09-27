package com.shivang.obd.audio;

import java.io.InputStream;
import java.util.UUID;

/**
 * Audio-owned storage seam (VB-5B).
 * <p>
 * Business logic persists only the logical {@code storageReference} this
 * interface returns; the physical location (local filesystem today, object
 * storage later) is an implementation detail. Campaign/readiness/runtime
 * code never touches physical storage — that is the entire point of the
 * seam. Deliberately NOT a generic storage framework: one method, one
 * audio-specific result record.
 */
public interface AudioStorage {

    /**
     * Stores the uploaded audio bytes.
     *
     * @param tenantId owning tenant (used for tenant-isolated layout)
     * @param audioAssetId owning asset id (dedicated per-asset directory)
     * @param originalFileName client-supplied file name; implementations
     *                         must derive a safe server-side name and never
     *                         concatenate it into a physical path
     * @param content audio bytes
     * @return descriptor containing the logical storage reference and
     *         verified byte size
     * @throws AudioStorageException on any storage failure (write, move,
     *                               verification). No partial file is left
     *                               exposed on failure.
     */
    StoredAudio store(UUID tenantId, UUID audioAssetId, String originalFileName, InputStream content);

    /**
     * Best-effort physical cleanup used by failure compensation (e.g. the
     * metadata persist step failed after a successful write). Must be
     * idempotent and never throw: filesystem is not transactional with
     * PostgreSQL, so cleanup is a compensation, not a guarantee.
     *
     * @param storageReference reference previously returned by
     *                         {@link #store}; non-references (blank/null)
     *                         are ignored
     */
    void delete(String storageReference);

    /** Result of a successful store. */
    record StoredAudio(String storageReference, long sizeBytes) {
    }
}
