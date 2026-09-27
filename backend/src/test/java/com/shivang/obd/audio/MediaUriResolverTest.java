package com.shivang.obd.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VB-6E — audio storage reference to FreeSWITCH-readable media path.
 *
 * <p>Pre-VB-6E the raw logical reference
 * ({@code audio/{tenant}/{asset}/{file}}) was handed straight to
 * {@code uuid_broadcast}, which FreeSWITCH resolves against its own sound
 * directory. Playback therefore could not succeed in any real deployment. These
 * tests pin the translation, and — because that value reaches a telephony
 * command — they pin the refusals just as tightly.
 */
class MediaUriResolverTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000000b");
    private static final UUID ASSET = UUID.fromString("cccccccc-0000-4000-8000-00000000000c");
    private static final UUID OTHER_ASSET = UUID.fromString("dddddddd-0000-4000-8000-00000000000d");
    private static final String MEDIA_ROOT = "/usr/share/freeswitch/sounds";

    private MediaUriResolver resolver;

    @BeforeEach
    void setUp() {
        AudioStorageProperties properties = new AudioStorageProperties();
        properties.setEnabled(true);
        properties.setBaseDirectory("data/audio");
        properties.setFreeswitchMediaRoot(MEDIA_ROOT);
        resolver = new MediaUriResolver(properties);
    }

    private static String reference(UUID tenant, UUID asset, String file) {
        return "audio/" + tenant + "/" + asset + "/" + file;
    }

    @Nested
    class HappyPath {

        @Test
        @DisplayName("URI-1: a valid reference maps to a FreeSWITCH-readable absolute path")
        void validReferenceMaps() {
            String uri = resolver.resolveMediaUri(
                    reference(TENANT, ASSET, "1f2e.wav"), ASSET, TENANT);

            assertThat(uri)
                    .isEqualTo(MEDIA_ROOT + "/" + TENANT + "/" + ASSET + "/1f2e.wav")
                    .startsWith("/")
                    .as("must be absolute, because FreeSWITCH resolves relative to its sound dir")
                    .doesNotContain("..");
        }

        @Test
        @DisplayName("URI-2: the mapping is deterministic")
        void mappingIsDeterministic() {
            String ref = reference(TENANT, ASSET, "1f2e.wav");

            String first = resolver.resolveMediaUri(ref, ASSET, TENANT);
            String second = resolver.resolveMediaUri(ref, ASSET, TENANT);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("URI-3: the mapping is independent of where the application writes")
        void mappingIsIndependentOfWriteLocation() {
            // The application base directory must not leak into the media path,
            // or a containerised FreeSWITCH would look in the wrong place.
            AudioStorageProperties elsewhere = new AudioStorageProperties();
            elsewhere.setBaseDirectory("/some/other/host/path");
            elsewhere.setFreeswitchMediaRoot(MEDIA_ROOT);
            MediaUriResolver relocated = new MediaUriResolver(elsewhere);

            assertThat(relocated.resolveMediaUri(reference(TENANT, ASSET, "a.wav"), ASSET, TENANT))
                    .isEqualTo(resolver.resolveMediaUri(
                            reference(TENANT, ASSET, "a.wav"), ASSET, TENANT));
        }

        @Test
        @DisplayName("URI-4: a configured media root is honoured and trailing separators normalised")
        void mediaRootIsHonoured() {
            AudioStorageProperties custom = new AudioStorageProperties();
            custom.setFreeswitchMediaRoot("/opt/fs/sounds///");
            MediaUriResolver relocated = new MediaUriResolver(custom);

            assertThat(relocated.resolveMediaUri(reference(TENANT, ASSET, "a.wav"), ASSET, TENANT))
                    .isEqualTo("/opt/fs/sounds/" + TENANT + "/" + ASSET + "/a.wav");
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        @DisplayName("URI-5: a reference naming another tenant is refused")
        void foreignTenantReferenceRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    reference(OTHER_TENANT, ASSET, "a.wav"), ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different tenant");
        }

        @Test
        @DisplayName("URI-6: a null tenant is refused rather than resolved")
        void nullTenantRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    reference(TENANT, ASSET, "a.wav"), ASSET, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class AssetIdentity {

        @Test
        @DisplayName("URI-7: a reference naming a different asset is refused")
        void foreignAssetReferenceRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    reference(TENANT, OTHER_ASSET, "a.wav"), ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not name the requested audio asset");
        }

        @Test
        @DisplayName("URI-8: a null asset is refused")
        void nullAssetRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    reference(TENANT, ASSET, "a.wav"), null, TENANT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TraversalAndShape {

        @Test
        @DisplayName("URI-9: parent traversal in the file name is refused")
        void parentTraversalRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    reference(TENANT, ASSET, "../../../etc/passwd"), ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("illegal path segment");
        }

        @Test
        @DisplayName("URI-10: traversal in the tenant segment is refused")
        void traversalInTenantSegmentRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    "audio/../other/tenant/a.wav", ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "URI-11: {0} is refused")
        @ValueSource(strings = {
                "audio/tenant/asset",                                  // too few segments
                "audio/a/b/c/d",                                        // too many segments
                "/absolute/audio/a/b.wav",                              // absolute
                "C:/audio/a/b.wav",                                     // windows drive
                "file:///etc/passwd",                                   // url scheme
                "http://host/x",                                        // http scheme
                "audio//a/b.wav",                                       // doubled separator
                "audio/a/b/",                                           // trailing separator
                "audio/tenant/asset/\0.wav",                            // embedded NUL
                "audio/tenant\\asset/a.wav",                            // backslash separator
        })
        @DisplayName("URI-11: malformed references are refused")
        void malformedReferencesRefused(String reference) {
            assertThatThrownBy(() -> resolver.resolveMediaUri(reference, ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "URI-12: missing reference {0} is refused")
        @ValueSource(strings = {"", "   "})
        @DisplayName("URI-12: a blank reference is refused")
        void blankReferenceRefused(String reference) {
            assertThatThrownBy(() -> resolver.resolveMediaUri(reference, ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("URI-13: a null reference is refused")
        void nullReferenceRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(null, ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("URI-14: a wrong prefix is refused")
        void wrongPrefixRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    "uploads/" + TENANT + "/" + ASSET + "/a.wav", ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must start with");
        }

        @Test
        @DisplayName("URI-15: a non-UUID tenant segment is refused")
        void nonUuidTenantSegmentRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    "audio/not-a-uuid/" + ASSET + "/a.wav", ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed tenant");
        }

        @Test
        @DisplayName("URI-16: a control character in the file name is refused")
        void controlCharacterRefused() {
            assertThatThrownBy(() -> resolver.resolveMediaUri(
                    reference(TENANT, ASSET, "a\u0001b.wav"), ASSET, TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");
        }
    }

    @Nested
    class ResolvabilityProbe {

        @Test
        @DisplayName("URI-17: isResolvable agrees with resolveMediaUri")
        void probeAgreesWithResolve() {
            assertThat(resolver.isResolvable(reference(TENANT, ASSET, "a.wav"))).isTrue();
            assertThat(resolver.isResolvable("../etc/passwd")).isFalse();
            assertThat(resolver.isResolvable(null)).isFalse();
            assertThat(resolver.isResolvable("nonsense")).isFalse();
        }
    }
}
