# VB-5B — Audio Upload & Storage Wiring — Implementation Report

## 1. Status

**VB-5B — COMPLETE**

## 2. Baseline

Recorded immediately before implementation (fresh `./mvnw clean test` during VB-5A, same session):

- **701 tests, 0 failures, 13 errors, 1 skipped**
- 13 pre-existing errors in 3 classes (ArchitectureTest, ProvisioningSmokeIntegrationTest, SecuritySliceTest) — carried since VB-0 era
- 2 pre-existing modulith cycles (`campaign ↔ voice ↔ telephony`)

## 3. Scope Implemented

- Multipart audio upload on the **existing** `AudioAssetController` (`POST /api/v1/audio-assets/upload`)
- Narrow audio-owned `AudioStorage` seam + `LocalAudioStorage` (local filesystem)
- `NoOpAudioStorage` when `audio.storage.enabled=false` (default; mirrors `NoOpVoiceMediaController` convention)
- Server-side validation: WAV/MP3 magic-byte detection, MIME cross-check, size limit, filename sanitization
- Derived metadata: canonical content type, byte size, SHA-256 checksum, best-effort WAV duration (null-safe)
- `storageReference` population (server-generated, tenant-isolated, traversal-safe)
- `CampaignReadinessService` storage-reference gate (closes the VB-5A readiness gap)
- Compensation for filesystem/DB non-atomicity
- Unit, filesystem (@TempDir), and real-PostgreSQL integration tests

**Not implemented (by contract):** S3/object storage, transcoding, TTS synthesis/global scope, DID lifecycle, deduplication, garbage collection, any generic storage framework.

## 4. API Changes

`POST /api/v1/audio-assets/upload` — `multipart/form-data`:

| Part | Required | Notes |
|---|---|---|
| `name` | yes | display name (max 150) |
| `description` | no | |
| `file` | yes | binary WAV/MP3; original filename used only for extension + display |

- Response: `201` with the standard `ApiResponse<AudioAssetResponse>` envelope. `storageReference` is included exactly as the existing DTO already exposed it (metadata-only assets previously exposed it too — semantics preserved, now it is actually populated).
- The client **cannot** supply contentType, fileSize, checksum, durationSeconds, status, tenantId, or storageReference — all derived server-side or taken from the organization context.
- Errors: `400` unsupported/invalid/oversized audio (existing `VALIDATION_ERROR` envelope), `401` unauthenticated, `403` missing `AUDIO_MANAGE`, `409` storage disabled (`AudioStorageException` → existing conflict handling).
- The existing JSON `POST /api/v1/audio-assets` (metadata registration) is unchanged for backward compatibility.

## 5. Storage Architecture

```text
AudioAssetController (multipart)
      ↓
AudioAssetService.upload()
      ├─ context tenant + AUDIO_MANAGE capability
      ├─ AudioUploadValidator.validate()   (single pass: signature, size, SHA-256, WAV duration)
      ├─ repository.save(entity)           (status = PENDING_APPROVAL, no storage reference)
      ├─ AudioStorage.store(tenantId, assetId, name, bytes)
      │       ↓
      │   LocalAudioStorage                ({base}/{tenantId}/{assetId}/{uuid}.{ext})
      │       ↓  temp file → verified → atomic move
      ├─ entity.setStorageReference("audio/{tenantId}/{assetId}/{uuid}.{ext}")
      └─ repository.save(entity)           (second save)
```

Failure compensation: if `store()` or the size verification throws, the service deletes the physical file (best-effort, idempotent) and rethrows — the transaction rolls back the metadata row. No partial file is ever exposed; `AudioStorage.store` writes to a temp file and uses `ATOMIC_MOVE`, with leftover temp files deleted in a `finally`.

## 6. Storage Reference

Format: `audio/{tenantId}/{audioAssetId}/{uuid}.{extension}`

- **The base directory configured via `audio.storage.base-directory` plays the role of the logical `audio/` root.** FreeSWITCH's configured sound/path environment maps this root to physical storage; Java never resolves it.
- Safe because: every segment except the validated extension (`[a-z0-9]{1,5}`) is a server-generated UUID; the original filename never enters a path; `LocalAudioStorage.delete()` normalizes and refuses any reference that resolves outside the base directory; `verifyWithinDirectory()` re-checks containment after every write (defense-in-depth).

## 7. Validation

| Check | Behavior |
|---|---|
| Formats | WAV (RIFF/WAVE) and MP3 (ID3 or bare MPEG sync) only — magic-byte detection is authoritative |
| MIME | Client-declared type must be an accepted alias and must be **compatible** with the detected signature (`audio/x-wav`/`audio/wave`/`audio/vnd.wave` → WAV; `audio/mp3` → MP3). A renamed `.exe` is rejected by signature. |
| Size | `audio.storage.max-file-size-bytes` (default 5 MiB, dev config); rejected **before** storage work; empty files rejected |
| Filename | Extension whitelist only; basename sanitized for display (`../x.wav` → `x.wav`); never used in paths |
| Checksum | SHA-256 hex of the actual stored bytes, computed in the validation pass; persisted to the existing `checksum` column |
| Duration | Parsed from RIFF fmt/data chunks (PCM 1, float 3, extensible 0xFFFE subformat 1/3). Sub-second data or parse failure → `duration_seconds = null` (column is nullable; DB CHECK `> 0` respected — this was a real constraint found by the integration tests). MP3 duration not parsed in VB-5B. |

**Limitation:** sub-second WAV clips persist `durationSeconds = null` (rounded 0 is discarded rather than violating the schema). No FFmpeg/transcoding — no codec-level verification beyond container signatures.

## 8. Approval

- Upload always persists `PENDING_APPROVAL`. There is no upload path that sets `APPROVED`.
- The existing `PATCH /{id}/approve|reject` endpoints, `AUDIO_APPROVE` capability, 409-on-repeat-transition, and platform approval behavior are untouched (verified by `AudioAssetServiceUploadTest.uploadNeverApproves` and the unchanged approval suites).

## 9. Campaign Readiness

`CampaignReadinessService.checkAudioAsset` now performs a second scoped EXISTS when the asset is otherwise usable:

- APPROVED + tenant-owned + live + **storageReference present** → no audio reason
- APPROVED + tenant-owned + live + **storageReference missing** → new reason `AUDIO_STORAGE_REFERENCE_MISSING` ("Audio asset is approved but has no stored audio file.")
- PENDING_APPROVAL / REJECTED / deleted / wrong-tenant → existing `AUDIO_NOT_APPROVED` (unchanged)
- Runtime validation in `PlayfileExecutionService` remains fully intact (defense against post-activation changes).

Intentional behavior change (documented per §36 of the contract): pre-VB-5B metadata-only assets that previously passed readiness now fail activation with `AUDIO_STORAGE_REFERENCE_MISSING`.

## 10. Security

- **Tenant isolation**: upload resolves the tenant exclusively from `OrganizationContextHolder` (platform callers are rejected); all lookups use `findByIdAndTenantIdAndDeletedAtIsNull`; proven by unit + PG tests (Tenant A cannot load Tenant B's asset; cross-tenant campaign references fail readiness).
- **Authorization**: reuse of `AUDIO_MANAGE` (upload), `AUDIO_VIEW`, `AUDIO_APPROVE` — no new capabilities.
- **Path traversal**: references are UUID-based; extension whitelist; containment verification after writes; `delete()` refuses non-`audio/` or escaping references. Tests prove `../../etc/passwd.wav` and `C:\Windows\evil.wav` cannot shape the stored path.
- **No filesystem writes outside the configured base**; no client-controlled directory or path.
- Logging stays at the existing SLF4J conventions; no audio bytes or credentials are logged.

## 11. Database

**No migration required.** V20's `audio_assets` schema (file_name, content_type, file_size, duration_seconds, checksum, storage_reference) fully supports VB-5B. The Flyway chain is exercised V1→V40 on a fresh `postgres:16-alpine` container by the new integration tests.

## 12. Tests

| Suite | Count | Result |
|---|---|---|
| `AudioUploadAndStorageTest` (validator + LocalAudioStorage, @TempDir) | 15 | 15/15 ✓ |
| `AudioAssetServiceUploadTest` (service-level, Mockito) | 6 | 6/6 ✓ |
| `AudioUploadPostgresIntegrationTest` (real PostgreSQL, Flyway V1..V40) | 8 | 8/8 ✓ |
| **VB-5B total** | **29** | **29/29 ✓** |
| Full regression (`./mvnw test`) | 730 | **0 failures, 13 errors, 1 skipped** |

PG integration coverage: upload persistence (metadata + physical file), tenant isolation, upload-never-approves, readiness for approved+stored, readiness rejection for approved-without-storage, pending/rejected unusable, soft-delete invisible, cross-tenant campaign reference rejected. Docker-unavailable environments abort (BLOCKED), never silently pass — per the established harness contract.

## 13. Defects Found/Fixed

1. **DEFECT-001 — `repository.save()` merge-path failure for pre-assigned UUIDs (production, VB-5B code)**
   Problem: with `entity.setId(UUID.randomUUID())` before the first save, Spring Data takes the **merge** path (`SimpleJpaRepository.save` → `em.merge`) for a still-transient row; the second save then threw `StaleObjectStateException` on every real upload.
   Root cause: Spring Data treats any non-null id as "existing row"; `BaseEntity` uses `@UuidGenerator` precisely to avoid this.
   Fix: never pre-assign the id in `upload()`; the first `save()` lets `@UuidGenerator` generate it, and the storage layout uses the entity's assigned id afterward.
   Test: `AudioUploadPostgresIntegrationTest` (all upload tests exercise the two-save flow; PG-1 proves both saves land).

2. **DEFECT-002 — WAV duration parser read fields at the chunk marker instead of marker+8 (VB-5B code)**
   Problem: `fmt ` fields were decoded 8 bytes early → garbage duration on every WAV.
   Fix: chunk field offsets corrected (`fmt` data at marker+8, `data` size at marker+4).
   Test: `validWav` (8 kHz/8-bit/mono, 1 s expected), plus PG-level proof via `ck_audio_assets_duration`.

3. **DEFECT-003 — sub-second WAV produced `durationSeconds = 0`, violating `ck_audio_assets_duration (> 0)`**
   Problem: `Math.round(usable/byteRate)` yields 0 for <0.5 s audio; the DB CHECK rejects the insert.
   Fix: durations that round to 0 persist as `null` (duration is optional metadata by design).
   Test: `tenantIsolation`/`softDeletedAssetUnusable` (previously failed on the constraint with 16-byte payloads; now green).

4. **DEFECT-004 — stale VB-3 comment claimed `pg_advisory_xact_unlock` "works" (documentation-only, pre-existing)**
   Found during the adjacent hardening: not VB-5B scope; left untouched here (already corrected in VB-4F for `VoiceCapacityServiceImpl`).

(No pre-existing baseline test was modified or weakened.)

## 14. Architecture

- Cycle count unchanged: **2** pre-existing groups (`campaign ↔ voice ↔ telephony`), no audio/TTS/DID involvement.
- `audio/` remains a leaf: imports from audio are limited to `authz`, `common`, `security`, `tenant` — **no** `campaign`/`voice`/`telephony` dependencies (verified by import scan).
- The FS-facing media boundary (`VoiceMediaController` → `FreeSwitchVoiceMediaController` → `EslClient.playFile` → `uuid_broadcast`) is untouched.
- `AudioStorageProperties` registered in `ObdApplication` per the `@EnableConfigurationProperties` convention.

## 15. Limitations

- Local filesystem storage only; object storage requires only a new `AudioStorage` implementation + configuration (no business changes).
- No transcoding/codec validation beyond container signatures; FreeSWITCH format compatibility (sample rates/codecs) is deployment configuration, documented as an assumption.
- WAV duration only (PCM/float/extensible); MP3 duration null.
- Sub-second WAVs persist null duration.
- No upload deduplication (checksum is metadata, not a unique constraint).
- No physical-file deletion on soft delete (deliberate MVP choice; no GC).
- Single-node local storage implies a shared/persistent volume when running multiple app instances or Docker (mount `AUDIO_STORAGE_BASE_DIR`).

## 16. Files Changed

**Production (new):** `audio/AudioStorage.java`, `audio/LocalAudioStorage.java`, `audio/NoOpAudioStorage.java`, `audio/AudioStorageProperties.java`, `audio/AudioStorageException.java`, `audio/AudioUploadValidator.java`, `audio/InvalidAudioUploadException.java`
**Production (modified):** `audio/AudioAssetService.java` (upload flow + compensation), `audio/AudioAssetController.java` (multipart endpoint + tag text), `audio/AudioAssetRepository.java` (readiness EXISTS finder), `campaign/CampaignReadinessService.java` (storage gate), `ObdApplication.java` (properties registration), `application-dev.yaml` (audio.storage config)
**Migrations:** none
**Tests (new):** `src/test/java/com/shivang/obd/audio/AudioUploadAndStorageTest.java`, `AudioAssetServiceUploadTest.java`, `AudioUploadPostgresIntegrationTest.java`
**Docs:** `docs/VB-5B-IMPLEMENTATION-REPORT.md` (this file); VB-5A inspection report unchanged

## 17. Final DoD

- Multipart upload on the existing audio resource: ✓ (`/upload`)
- Form accepts name/description/file; tenant from server context: ✓
- Format validation (WAV/MP3 signature): ✓ · size validation (configurable): ✓ · SHA-256: ✓ · server-side size/type: ✓
- MIME/content validation incl. renamed-binary rejection: ✓
- Duration extracted where safely possible (WAV), otherwise null — documented: ✓
- Physical storage through `AudioStorage`; `LocalAudioStorage` works; `storageReference` persisted: ✓
- Client cannot control path; traversal prevented: ✓
- Upload never approves; approval flow/authorization intact: ✓
- Readiness: approved+stored passes; approved+missing-storage fails (`AUDIO_STORAGE_REFERENCE_MISSING`); pending/rejected/deleted unusable: ✓
- Runtime validation intact: ✓ (`PlayfileExecutionService` untouched)
- Tenant isolation + authorization verified (unit + PG): ✓ · no arbitrary writes/path leakage: ✓
- No generic storage framework; config externalized; tests use temp dirs; failure cleanup handled: ✓
- No new modulith cycle; audio independent of campaign; storage is audio-side; FS boundary unchanged: ✓
- VB-0…VB-4F regression green; new tests green; PG integration green; no unexplained new failures: ✓

## 18. Final Verdict

**READY FOR VB-5C**
