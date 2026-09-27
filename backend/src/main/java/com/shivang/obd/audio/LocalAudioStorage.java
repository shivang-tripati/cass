package com.shivang.obd.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-filesystem {@link AudioStorage} (VB-5B).
 * <p>
 * Layout: the base directory plays the role of the logical {@code audio/}
 * root: {@code {base}/{tenantId}/{audioAssetId}/{uuid}.{ext}} with logical
 * reference {@code audio/{tenantId}/{audioAssetId}/{uuid}.{ext}} — every
 * path segment is server-generated from trusted identifiers, so the client
 * can neither select a directory nor inject traversal sequences. The
 * original file name is used ONLY for its extension (validated by the
 * caller) and never concatenated into a physical path.
 * <p>
 * Writes go to a temporary file in the same directory and are moved into
 * place atomically, so a partially written file is never visible. The
 * stored size is verified against what was read before the move is
 * acknowledged. {@link #delete(String)} is a best-effort idempotent
 * compensation — filesystem operations are not transactional with
 * PostgreSQL, so cleanup complements (never replaces) the DB lifecycle.
 */
@Component
@ConditionalOnProperty(prefix = "audio.storage", name = "enabled", havingValue = "true")
public class LocalAudioStorage implements AudioStorage {

    private final AudioStorageProperties properties;

    public LocalAudioStorage(AudioStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredAudio store(UUID tenantId, UUID audioAssetId, String originalFileName, InputStream content) {
        if (tenantId == null || audioAssetId == null) {
            throw new AudioStorageException("Tenant and asset identifiers are required for storage.");
        }
        if (content == null) {
            throw new AudioStorageException("Audio content is required for storage.");
        }
        String extension = safeExtension(originalFileName);

        Path targetDir = Path.of(properties.getBaseDirectory())
            .toAbsolutePath()
            .normalize()
            .resolve(tenantId.toString())
            .resolve(audioAssetId.toString());
        Path target = targetDir.resolve(UUID.randomUUID() + extension);

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new AudioStorageException("Cannot create audio storage directory: " + e.getMessage(), e);
        }

        Path temp = null;
        try {
            temp = Files.createTempFile(targetDir, "upload-", ".tmp");
            long bytes = content.transferTo(Files.newOutputStream(temp));
            if (bytes <= 0) {
                throw new AudioStorageException("Audio content is empty.");
            }
            verifyWithinDirectory(targetDir, temp);
            Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            return new StoredAudio(
                "audio/" + tenantId + "/" + audioAssetId + "/" + target.getFileName(),
                bytes);
        } catch (IOException e) {
            throw new AudioStorageException("Failed to store audio: " + e.getMessage(), e);
        } finally {
            // A leftover temp file (failed transfer, failed verification,
            // failed move) is compensated immediately.
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort only; never mask the primary failure.
                }
            }
        }
    }

    @Override
    public void delete(String storageReference) {
        if (storageReference == null || storageReference.isBlank()) {
            return;
        }
        if (!storageReference.startsWith("audio/")) {
            return; // Not a reference this implementation produced.
        }
        Path base = Path.of(properties.getBaseDirectory()).toAbsolutePath().normalize();
        Path path = base.resolve(storageReference.substring("audio/".length())).normalize();
        if (!path.startsWith(base)) {
            // Traversal-shaped reference — refuse to touch anything outside base.
            return;
        }
        try {
            Files.deleteIfExists(path);
            // Best-effort parent cleanup so empty per-asset directories do not accumulate.
            Path parent = path.getParent();
            if (parent != null && !parent.equals(base)) {
                try (var entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
        } catch (IOException e) {
            // Idempotent compensation only — never propagate to callers.
        }
    }

    /** Extracts a validated extension, or an empty string; never any separator. */
    private String safeExtension(String originalFileName) {
        if (originalFileName == null) {
            return "";
        }
        String name = originalFileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).trim().toLowerCase();
        // Strict extension whitelist: only characters/lengths that match
        // the supported audio formats. Path separators, dots and traversal
        // sequences can never survive this check.
        if (!ext.matches("[a-z0-9]{1,5}")) {
            return "";
        }
        return "." + ext;
    }

    /**
     * Defense-in-depth: after every write, assert the temp file still
     * resolves inside the intended directory (guards against any future
     * change accidentally escaping the base directory).
     */
    private static void verifyWithinDirectory(Path dir, Path file) throws IOException {
        Path realDir = dir.toRealPath();
        if (!file.toRealPath().startsWith(realDir)) {
            throw new IOException("Storage path escaped the audio base directory");
        }
    }
}
