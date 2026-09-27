package com.shivang.obd.audio;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Translates an audio asset's <em>logical</em> storage reference into a URI
 * FreeSWITCH can actually read (VB-6E).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Before VB-6E the logical reference was forwarded verbatim:
 * {@code PlayfileExecutionService} passed {@code asset.getStorageReference()}
 * straight to {@code VoiceMediaController.playAudio}, and the FreeSWITCH
 * adapter interpolated it into
 * {@code uuid_broadcast <channel> <path> aleg}. That string is
 * {@code audio/<tenant>/<assetId>/<file>.wav} — an application-relative
 * identifier written under the application's own storage base directory.
 * FreeSWITCH resolves a relative {@code playFile} argument against its
 * <em>own</em> sound directory, and no sounds directory was ever configured,
 * so playback could not succeed in any real deployment.
 *
 * <h2>The contract</h2>
 *
 * <p>This component is the single place a storage reference becomes a media
 * path. It is deliberately strict, because the value it produces is handed to
 * a telephony command:
 *
 * <ul>
 *   <li><b>Shape is validated first.</b> The reference must match the exact
 *       grammar {@code audio/{tenantId}/{assetId}/{fileName}} — exactly three
 *       segments, a fixed prefix, and two parseable UUIDs. Anything else is
 *       rejected, which makes {@code ../} traversal, absolute paths, URLs,
 *       doubled separators and extra segments unrepresentable rather than
 *       merely filtered.</li>
 *   <li><b>The embedded tenant must match the caller's tenant.</b> A reference
 *       is tenant-scoped by construction, so a mismatched one is refused rather
 *       than trusted.</li>
 *   <li><b>The embedded asset id must match the asset being played.</b> A
 *       reference cannot be re-pointed at a different asset.</li>
 *   <li><b>No path is touched.</b> This resolves a name; it does not stat,
 *       open, copy or move anything. Existence is the storage layer's concern
 *       and is already gated by the campaign-side resource validator's audio
 *       classification (APPROVED status plus a non-blank reference).</li>
 * </ul>
 *
 * <p>Resulting paths are POSIX-style and rooted at
 * {@link AudioStorageProperties#getFreeswitchMediaRoot()} joined with the
 * reference segments, so the reference stays independent of the deployment
 * layout and of where the application itself writes the bytes.
 *
 * <h2>Not in scope</h2>
 *
 * <p>Object storage / CDN-backed media is explicitly out of VB-6E. This type
 * exists so that adding it later is a new implementation behind the same
 * decision, not a change to the campaign path.
 */
@Component
public class MediaUriResolver {

    /** The one accepted reference prefix, matching {@code LocalAudioStorage}. */
    static final String REFERENCE_PREFIX = "audio/";

    /**
     * FreeSWITCH-relative media root. Configured under
     * {@code audio.storage} because it is an audio concern; it defaults to the
     * FreeSWITCH convention so a standard deployment needs no extra
     * configuration.
     */
    private final AudioStorageProperties storageProperties;

    public MediaUriResolver(AudioStorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * Resolves a validated asset's storage reference to a FreeSWITCH-readable
     * POSIX path.
     *
     * @param storageReference the logical reference from the audio asset
     * @param assetId          the audio asset the reference is expected to name
     * @param tenantId         the owning tenant, which the reference must embed
     * @return an absolute POSIX path FreeSWITCH can open
     * @throws IllegalArgumentException when the reference is malformed, names a
     *                                  different asset or tenant, or contains any
     *                                  traversal or separator anomaly
     */
    public String resolveMediaUri(String storageReference, UUID assetId, UUID tenantId) {
        String normalized = requireWellFormedReference(storageReference);
        UUID referenceTenant = requireTenantSegment(normalized, tenantId);
        UUID referenceAsset = requireAssetSegment(normalized, assetId);
        requireSafeFileName(normalized);

        // The two UUID segments are already parsed, so the path cannot escape.
        return mediaRoot()
                + "/" + referenceTenant
                + "/" + referenceAsset
                + "/" + fileNameOf(normalized);
    }

    /**
     * True when the reference is syntactically usable. Used by the audio
     * validator so an unusable reference is refused at configuration time
     * rather than at dial time.
     */
    public boolean isResolvable(String storageReference) {
        try {
            requireWellFormedReference(storageReference);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    private String requireWellFormedReference(String storageReference) {
        if (storageReference == null || storageReference.isBlank()) {
            throw new IllegalArgumentException("Audio storage reference is required");
        }
        String reference = storageReference.trim();

        if (!reference.startsWith(REFERENCE_PREFIX)) {
            throw new IllegalArgumentException(
                    "Audio storage reference must start with '" + REFERENCE_PREFIX + "'");
        }
        // Reject before splitting: a scheme, a UNC prefix, an absolute path, a
        // parent reference, an embedded NUL or a backslash separator.
        String relative = reference.substring(REFERENCE_PREFIX.length());
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.contains("..") || lower.contains("\\") || relative.indexOf('\0') >= 0
                || lower.startsWith("/") || lower.contains("://")) {
            throw new IllegalArgumentException(
                    "Audio storage reference contains an illegal path segment");
        }
        if (relative.contains("//") || relative.endsWith("/")) {
            throw new IllegalArgumentException(
                    "Audio storage reference is not a canonical path");
        }
        return relative;
    }

    private UUID requireTenantSegment(String relative, UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant is required to resolve an audio reference");
        }
        String[] segments = split(relative);
        if (segments.length != 3) {
            throw new IllegalArgumentException(
                    "Audio storage reference must be audio/{tenant}/{asset}/{file}");
        }
        UUID referenceTenant = parseUuid(segments[0], "tenant");
        if (!referenceTenant.equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Audio storage reference belongs to a different tenant");
        }
        return referenceTenant;
    }

    private UUID requireAssetSegment(String relative, UUID assetId) {
        if (assetId == null) {
            throw new IllegalArgumentException(
                    "Audio asset is required to resolve an audio reference");
        }
        String[] segments = split(relative);
        UUID referenceAsset = parseUuid(segments[1], "asset");
        if (!referenceAsset.equals(assetId)) {
            throw new IllegalArgumentException(
                    "Audio storage reference does not name the requested audio asset");
        }
        return referenceAsset;
    }

    private void requireSafeFileName(String relative) {
        String fileName = fileNameOf(relative);
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException(
                    "Audio storage reference has no usable file name");
        }
        // A server-generated name only; no separators, no control characters.
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(
                        "Audio storage reference file name contains a control character");
            }
        }
    }

    private static String[] split(String relative) {
        return relative.split("/", -1);
    }

    private static String fileNameOf(String relative) {
        int lastSlash = relative.lastIndexOf('/');
        return lastSlash < 0 ? relative : relative.substring(lastSlash + 1);
    }

    private static UUID parseUuid(String segment, String label) {
        try {
            return UUID.fromString(segment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Audio storage reference has a malformed " + label + " segment");
        }
    }

    /**
     * The configured FreeSWITCH media root, normalised to a POSIX path with no
     * trailing separator. Exposed for diagnostics and for tests asserting a
     * deterministic mapping.
     */
    public String mediaRoot() {
        return stripTrailingSeparators(
                storageProperties.getFreeswitchMediaRoot().replace('\\', '/').trim());
    }

    private static String stripTrailingSeparators(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
