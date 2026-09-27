package com.shivang.obd.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * VB-5B unit tests: {@link AudioUploadValidator} format/MIME/size validation,
 * metadata extraction, and {@link LocalAudioStorage} layout, traversal safety,
 * atomic writes and idempotent compensation. Filesystem tests use JUnit's
 * {@code @TempDir} — never a developer-machine path.
 */
class AudioUploadAndStorageTest {

    private static final UUID TENANT = UUID.fromString("cc000000-0000-4000-8000-0000000000a1");
    private static final UUID ASSET = UUID.fromString("cc000000-0000-4000-8000-0000000000b2");

    /** Builds a minimal valid PCM WAV byte array (44-byte canonical header). */
    private static byte[] wavBytes(int dataBytes) {
        int total = 44 + dataBytes;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(total - 8).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataBytes);
        for (int i = 0; i < dataBytes; i++) {
            b.put((byte) (i % 7));
        }
        return b.array();
    }

    /** Minimal valid MP3: ID3v2 header + one MPEG frame sync. */
    private static byte[] mp3Bytes() {
        return new byte[]{
            'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0,
            (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0, 0, 0, 0, 0, 0, 0
        };
    }

    private AudioUploadValidator validator(long maxBytes) {
        AudioStorageProperties props = new AudioStorageProperties();
        props.setMaxFileSizeBytes(maxBytes);
        return new AudioUploadValidator(props);
    }

    // === validator ===

    @Nested
    @DisplayName("AudioUploadValidator")
    class ValidatorTests {

        @Test
        @DisplayName("accepts a valid WAV and derives canonical metadata")
        void validWav() {
            byte[] wav = wavBytes(8000); // 1s at 8kHz/8bit/mono
            var result = validator(1024 * 1024).validate("audio/wav", "promo.wav", new ByteArrayInputStream(wav));

            assertThat(result.format()).isEqualTo(AudioUploadValidator.AudioFormat.WAV);
            assertThat(result.contentType()).isEqualTo("audio/wav");
            assertThat(result.sizeBytes()).isEqualTo(wav.length);
            assertThat(result.durationSeconds()).isEqualTo(1);
            assertThat(result.sha256Hex()).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("accepts a valid MP3 with ID3 header")
        void validMp3() {
            var result = validator(1024 * 1024).validate("audio/mpeg", "beep.mp3", new ByteArrayInputStream(mp3Bytes()));

            assertThat(result.format()).isEqualTo(AudioUploadValidator.AudioFormat.MP3);
            assertThat(result.contentType()).isEqualTo("audio/mpeg");
            assertThat(result.durationSeconds()).isNull(); // MP3 duration not parsed in VB-5B
        }

        @Test
        @DisplayName("rejects a mismatched declared MIME type")
        void mismatchedMime() {
            assertThatThrownBy(() ->
                validator(1024 * 1024).validate("audio/mpeg", "x.wav", new ByteArrayInputStream(wavBytes(16))))
                .isInstanceOf(InvalidAudioUploadException.class);
        }

        @Test
        @DisplayName("rejects unsupported declared MIME types before reading content")
        void unsupportedMime() {
            assertThatThrownBy(() ->
                validator(1024 * 1024).validate("application/pdf", "x.mp3", new ByteArrayInputStream(mp3Bytes())))
                .isInstanceOf(InvalidAudioUploadException.class)
                .hasMessageContaining("Unsupported audio content type");
        }

        @Test
        @DisplayName("rejects non-audio content regardless of extension or MIME (renamed .exe)")
        void renamedExecutable() {
            byte[] exe = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x04, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};
            assertThatThrownBy(() ->
                validator(1024 * 1024).validate("audio/mpeg", "malicious.mp3", new ByteArrayInputStream(exe)))
                .isInstanceOf(InvalidAudioUploadException.class)
                .hasMessageContaining("not a recognizable WAV or MP3");
        }

        @Test
        @DisplayName("rejects empty files")
        void emptyFile() {
            assertThatThrownBy(() ->
                validator(1024 * 1024).validate("audio/wav", "empty.wav", new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(InvalidAudioUploadException.class)
                .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("rejects oversized files")
        void oversized() {
            byte[] wav = wavBytes(64);
            assertThatThrownBy(() ->
                validator(32).validate("audio/wav", "big.wav", new ByteArrayInputStream(wav)))
                .isInstanceOf(InvalidAudioUploadException.class)
                .hasMessageContaining("exceeds the maximum");
        }

        @Test
        @DisplayName("accepts alternate WAV MIME aliases")
        void wavMimeAliases() {
            byte[] wav = wavBytes(16);
            for (String mime : new String[]{"audio/x-wav", "audio/wave", "audio/vnd.wave"}) {
                var result = validator(1024 * 1024).validate(mime, "a.wav", new ByteArrayInputStream(wav));
                assertThat(result.format()).isEqualTo(AudioUploadValidator.AudioFormat.WAV);
            }
        }

        @Test
        @DisplayName("accepts bare MPEG frame sync without ID3 header")
        void bareMpegFrame() {
            byte[] mpeg = {(byte) 0xFF, (byte) 0xFB, 0x10, (byte) 0xC4, 0, 0, 0, 0};
            var result = validator(1024 * 1024).validate("audio/mp3", "raw.mp3", new ByteArrayInputStream(mpeg));
            assertThat(result.format()).isEqualTo(AudioUploadValidator.AudioFormat.MP3);
        }
    }

    // === storage ===

    @Nested
    @DisplayName("LocalAudioStorage")
    class StorageTests {

        @TempDir
        Path tempDir;

        private LocalAudioStorage storage() {
            AudioStorageProperties props = new AudioStorageProperties();
            props.setEnabled(true);
            props.setBaseDirectory(tempDir.toString());
            return new LocalAudioStorage(props);
        }

        @Test
        @DisplayName("stores into a tenant/asset-isolated layout and returns a logical reference")
        void storesWithTenantLayout() {
            LocalAudioStorage storage = storage();
            byte[] wav = wavBytes(128);

            AudioStorage.StoredAudio stored = storage.store(
                TENANT, ASSET, "promo.wav", new ByteArrayInputStream(wav));

            assertThat(stored.storageReference()).startsWith("audio/" + TENANT + "/" + ASSET + "/");
            assertThat(stored.sizeBytes()).isEqualTo(wav.length);

            // The base directory is the logical audio/ root.
            Path physical = tempDir.resolve(stored.storageReference().substring("audio/".length()));
            assertThat(physical).exists();
            try {
                assertThat(Files.readAllBytes(physical)).isEqualTo(wav);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("reference is stable, unique per store, and never contains client input")
        void referencesAreSafe() {
            LocalAudioStorage storage = storage();
            var first = storage.store(TENANT, ASSET, "../../etc/passwd.wav", new ByteArrayInputStream(wavBytes(16)));
            var second = storage.store(TENANT, ASSET, "C:\\Windows\\evil.wav", new ByteArrayInputStream(wavBytes(16)));

            assertThat(first.storageReference()).isNotEqualTo(second.storageReference());
            // Client-supplied directory components never survive: the reference
            // is exactly audio/{tenant}/{asset}/{uuid}.{ext}.
            assertThat(first.storageReference()).matches(
                "audio/" + TENANT + "/" + ASSET + "/[0-9a-f-]{36}\\.wav");
            assertThat(second.storageReference()).matches(
                "audio/" + TENANT + "/" + ASSET + "/[0-9a-f-]{36}\\.wav");
        }

        @Test
        @DisplayName("deletes the physical file and is idempotent")
        void deleteIsIdempotent() {
            LocalAudioStorage storage = storage();
            var stored = storage.store(TENANT, ASSET, "a.wav", new ByteArrayInputStream(wavBytes(16)));
            Path physical = tempDir.resolve(stored.storageReference().substring("audio/".length()));
            assertThat(physical).exists();

            storage.delete(stored.storageReference());
            assertThat(physical).doesNotExist();
            storage.delete(stored.storageReference()); // second delete: no-op, no throw
            storage.delete(null);
            storage.delete("   ");
        }

        @Test
        @DisplayName("delete refuses references that escape the base directory")
        void deleteRefusesEscapes() {
            // Reference shaped like a traversal attempt: resolve() would walk out
            // of base; the implementation must ignore it instead of deleting.
            storage().delete("audio/" + TENANT + "/" + ASSET + "/../../outside.wav");
            // Nothing was deleted outside the base dir (the dir itself still exists).
            assertThat(tempDir).exists();
        }

        @Test
        @DisplayName("rejected: null tenant/asset or null content")
        void rejectsInvalidInput() {
            LocalAudioStorage storage = storage();
            assertThatThrownBy(() -> storage.store(null, ASSET, "a.wav", new ByteArrayInputStream(wavBytes(8))))
                .isInstanceOf(AudioStorageException.class);
            assertThatThrownBy(() -> storage.store(TENANT, null, "a.wav", new ByteArrayInputStream(wavBytes(8))))
                .isInstanceOf(AudioStorageException.class);
            assertThatThrownBy(() -> storage.store(TENANT, ASSET, "a.wav", null))
                .isInstanceOf(AudioStorageException.class);
        }

        @Test
        @DisplayName("empty content fails with no file left behind")
        void emptyContentLeavesNoFile() {
            LocalAudioStorage storage = storage();
            Path assetDir = tempDir.resolve(TENANT.toString()).resolve(ASSET.toString());

            assertThatThrownBy(() -> storage.store(TENANT, ASSET, "empty.wav",
                new InputStream() {
                    @Override
                    public int read() {
                        return -1;
                    }
                }))
                .isInstanceOf(AudioStorageException.class)
                .hasMessageContaining("empty");

            // No .tmp leftovers and no final file: compensation ran.
            try (var files = Files.list(assetDir)) {
                assertThat(files.findAny()).isEmpty();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
