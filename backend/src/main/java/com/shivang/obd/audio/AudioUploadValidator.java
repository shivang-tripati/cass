package com.shivang.obd.audio;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * Server-side audio upload validation and metadata extraction (VB-5B).
 * <p>
 * Supports WAV and MP3 only — the formats the existing FreeSWITCH media
 * boundary is assumed to handle. Validation combines three signals:
 * <ol>
 *   <li>declared MIME type (client-supplied, low trust)</li>
 *   <li>magic-byte signature (authoritative, server-side)</li>
 *   <li>size limits (rejected before any storage work)</li>
 * </ol>
 * The browser-provided content type is never trusted alone: a
 * {@code .exe} renamed to {@code .mp3} is rejected by signature.
 * <p>
 * Checksum is the SHA-256 hex of the actual uploaded bytes, computed in
 * the same single pass that buffers the content. WAV duration is parsed
 * from the RIFF header (PCM float/int formats); a parsing failure is not
 * fatal — {@code durationSeconds} stays null, matching the existing
 * optional-duration contract.
 */
@Component
public class AudioUploadValidator {

    /** Maximum bytes read for validation/metadata; oversized uploads fail fast. */
    private final long maxFileSizeBytes;

    public AudioUploadValidator(AudioStorageProperties properties) {
        this.maxFileSizeBytes = properties.getMaxFileSizeBytes();
    }

    /** MIME types accepted from the client declaration (advisory signal). */
    private static final java.util.Set<String> ALLOWED_MIME = java.util.Set.of(
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
        "audio/mpeg", "audio/mp3");

    /** Canonical content types persisted per format. */
    private static final String WAV_TYPE = "audio/wav";
    private static final String MP3_TYPE = "audio/mpeg";

    public record ValidatedAudio(
        AudioFormat format,
        String contentType,
        long sizeBytes,
        String sha256Hex,
        Integer durationSeconds,
        byte[] content
    ) {
    }

    public enum AudioFormat { WAV, MP3 }

    /**
     * Validates the upload and extracts metadata in a single content pass.
     *
     * @throws InvalidAudioUploadException on any validation failure
     */
    public ValidatedAudio validate(String declaredContentType, String originalFileName, InputStream content) {
        if (content == null) {
            throw new InvalidAudioUploadException("Audio file is required.");
        }
        String mime = declaredContentType == null ? "" : declaredContentType.trim().toLowerCase();
        if (!ALLOWED_MIME.contains(mime)) {
            throw new InvalidAudioUploadException(
                "Unsupported audio content type: " + declaredContentType + " (supported: WAV, MP3)");
        }

        byte[] bytes;
        try {
            bytes = content.readNBytes((int) Math.min(maxFileSizeBytes + 1, Integer.MAX_VALUE));
        } catch (IOException e) {
            throw new InvalidAudioUploadException("Cannot read uploaded audio: " + e.getMessage());
        }
        if (bytes.length == 0) {
            throw new InvalidAudioUploadException("Audio file is empty.");
        }
        if (bytes.length > maxFileSizeBytes) {
            throw new InvalidAudioUploadException(
                "Audio file exceeds the maximum allowed size of " + maxFileSizeBytes + " bytes.");
        }

        AudioFormat format = detectFormat(bytes);
        String canonicalType = format == AudioFormat.WAV ? WAV_TYPE : MP3_TYPE;
        if (!isCompatible(mime, format)) {
            throw new InvalidAudioUploadException(
                "Declared content type does not match the actual audio content.");
        }

        Integer duration = null;
        if (format == AudioFormat.WAV) {
            duration = wavDurationSeconds(bytes); // best-effort; null on parse failure
        }

        return new ValidatedAudio(
            format,
            canonicalType,
            (long) bytes.length,
            sha256Hex(bytes),
            duration,
            bytes);
    }

    /** Magic-byte detection; the signature is authoritative over the declared type. */
    private AudioFormat detectFormat(byte[] bytes) {
        if (bytes.length >= 12
            && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return AudioFormat.WAV;
        }
        if (bytes.length >= 3
            && (bytes[0] == 'I') && (bytes[1] == 'D') && (bytes[2] == '3')) {
            return AudioFormat.MP3;
        }
        // Bare MPEG audio frame sync (0xFFEx) without an ID3 header.
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return AudioFormat.MP3;
        }
        throw new InvalidAudioUploadException(
            "File content is not a recognizable WAV or MP3 audio stream.");
    }

    private boolean isCompatible(String declaredMime, AudioFormat format) {
        boolean wavMime = mimeMatches(declaredMime, WAV_TYPE);
        boolean mp3Mime = mimeMatches(declaredMime, MP3_TYPE);
        return switch (format) {
            case WAV -> wavMime;
            case MP3 -> mp3Mime;
        };
    }

    private boolean mimeMatches(String declared, String canonical) {
        if (declared.equals(canonical)) {
            return true;
        }
        // audio/x-wav, audio/wave, audio/vnd.wave all describe WAV.
        if (AudioFormat.WAV == AudioFormat.WAV && declared.startsWith("audio/")
            && (declared.contains("wav") || declared.contains("wave"))) {
            return true;
        }
        // audio/mp3 and audio/mpeg both describe MP3.
        return declared.startsWith("audio/") && declared.contains("mp3") && canonical.equals(MP3_TYPE);
    }

    /** SHA-256 of the exact uploaded bytes. */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(bytes)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /**
     * Best-effort WAV duration from the RIFF/fmt chunk. Supports PCM
     * (format 1), IEEE float (3) and WAVE_FORMAT_EXTENSIBLE (0xFFFE with
     * PCM subformat) — the formats FreeSWITCH commonly plays. Returns
     * null on any parse failure (duration is optional metadata).
     */
    private static Integer wavDurationSeconds(byte[] b) {
        try {
            // Chunk positions: marker at pos, size at pos+4, fields at pos+8.
            int fmtChunk = findChunk(b, 12, "fmt ");
            if (fmtChunk < 0) {
                return null;
            }
            int audioFormat = le16(b, fmtChunk + 8);
            int channels = le16(b, fmtChunk + 10);
            long sampleRate = le32(b, fmtChunk + 12);
            long byteRate = le32(b, fmtChunk + 16);
            if (sampleRate <= 0 || byteRate <= 0 || channels <= 0) {
                return null;
            }
            if (audioFormat == 0xFFFE && fmtChunk + 34 <= b.length) {
                int subFormat = le16(b, fmtChunk + 32); // first 2 bytes of the GUID
                if (subFormat == 1 || subFormat == 3) {
                    audioFormat = subFormat;
                }
            }
            if (audioFormat != 1 && audioFormat != 3) {
                return null;
            }
            int dataChunk = findChunk(b, 12, "data");
            if (dataChunk < 0) {
                return null;
            }
            long dataSize = le32(b, dataChunk + 4);
            long available = b.length - (dataChunk + 8);
            long usable = Math.min(dataSize <= 0 ? available : dataSize, available);
            if (usable <= 0) {
                return null;
            }
            long seconds = usable / byteRate;
            return seconds <= 0 ? null : (int) Math.min(seconds, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            return null; // never fail an upload over optional metadata
        }
    }

    private static int findChunk(byte[] b, int from, String chunkId) {
        int pos = from;
        while (pos + 8 <= b.length) {
            if (b[pos] == chunkId.charAt(0) && b[pos + 1] == chunkId.charAt(1)
                && b[pos + 2] == chunkId.charAt(2) && (chunkId.length() < 4 || b[pos + 3] == chunkId.charAt(3))) {
                return pos;
            }
            long size = le32(b, pos + 4);
            pos += 8 + (int) size;
            if (size <= 0) {
                break;
            }
            pos += pos & 1; // RIFF chunks are word-aligned
        }
        return -1;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
            | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
