import { describe, expect, it } from "vitest";

import {
  AUDIO_ACCEPTED_MIME_TYPES,
  AUDIO_DESCRIPTION_MAX,
  AUDIO_MAX_UPLOAD_BYTES,
  AUDIO_NAME_MAX,
  audioUploadFormSchema,
  describeUploadFileProblem,
  toUpdateAudioAssetPayload,
  updateAudioAssetSchema,
} from "@/lib/schemas/audio-asset-mutation";

/**
 * F3 — the audio upload pre-flight and the metadata update schema.
 *
 * VERIFIED against `AudioUploadValidator`, `AudioStorageProperties`,
 * `AudioAssetService.upload` and the V20 `audio_assets` table.
 *
 * The framing matters: `describeUploadFileProblem` is a **pre-flight**, not
 * validation. The server decides the format from the file's magic bytes and
 * only cross-checks the declared MIME, so a `.exe` renamed to `.mp3` passes
 * everything here and is still rejected. These tests pin that the local check
 * catches only what it claims to catch, and does not pretend to be the authority.
 */
function fakeFile(overrides: Partial<Pick<File, "name" | "size" | "type">>) {
  return {
    name: "welcome.wav",
    size: 1024,
    type: "audio/wav",
    ...overrides,
  } as Pick<File, "name" | "size" | "type">;
}

describe("the accepted MIME list mirrors AudioUploadValidator.ALLOWED_MIME", () => {
  it("contains exactly the six declared types", () => {
    // VERIFIED AudioUploadValidator L39-41.
    expect([...AUDIO_ACCEPTED_MIME_TYPES]).toEqual([
      "audio/wav",
      "audio/x-wav",
      "audio/wave",
      "audio/vnd.wave",
      "audio/mpeg",
      "audio/mp3",
    ]);
  });
});

describe("describeUploadFileProblem", () => {
  it("accepts a normal WAV", () => {
    expect(describeUploadFileProblem(fakeFile({}))).toBeNull();
  });

  it("accepts a normal MP3", () => {
    expect(
      describeUploadFileProblem(
        fakeFile({ name: "welcome.mp3", type: "audio/mpeg" }),
      ),
    ).toBeNull();
  });

  it("rejects an empty file", () => {
    // VERIFIED: `bytes.length == 0` → "Audio file is empty."
    expect(describeUploadFileProblem(fakeFile({ size: 0 }))).toMatch(/empty/i);
  });

  it("rejects a file over the 5 MiB default", () => {
    // VERIFIED `AudioStorageProperties.maxFileSizeBytes` default.
    expect(AUDIO_MAX_UPLOAD_BYTES).toBe(5 * 1024 * 1024);
    const problem = describeUploadFileProblem(
      fakeFile({ size: AUDIO_MAX_UPLOAD_BYTES + 1 }),
    );
    expect(problem).toMatch(/5 MB/);
  });

  it("accepts a file exactly at the limit", () => {
    expect(
      describeUploadFileProblem(fakeFile({ size: AUDIO_MAX_UPLOAD_BYTES })),
    ).toBeNull();
  });

  it("rejects an unsupported extension", () => {
    for (const name of ["notes.txt", "clip.ogg", "clip.flac", "noextension"]) {
      expect(
        describeUploadFileProblem(fakeFile({ name })),
        `expected ${name} to be rejected`,
      ).not.toBeNull();
    }
  });

  it("accepts an uppercase extension — WAV and MP3 are case-insensitive here", () => {
    // The server sniffs magic bytes and never looks at the extension at all, so
    // rejecting `.WAV` on the client would be a false negative with no server
    // equivalent.
    expect(
      describeUploadFileProblem(fakeFile({ name: "WELCOME.WAV" })),
    ).toBeNull();
  });

  it("rejects a declared MIME type the server would refuse", () => {
    // VERIFIED: `ALLOWED_MIME.contains(mime)` is checked first, so a declared
    // `video/mp4` is a 400 before the bytes are even sniffed. A `.wav` file with
    // a video MIME isolates the declared-type branch from the extension branch.
    const problem = describeUploadFileProblem(
      fakeFile({ name: "clip.wav", type: "video/mp4" }),
    );
    expect(problem).toMatch(/WAV and MP3/);
  });

  it("reports the extension first, because that is the likelier mistake", () => {
    // An `.mp4` is rejected on its extension regardless of what the browser
    // claims, which is the more actionable message.
    expect(
      describeUploadFileProblem(fakeFile({ name: "clip.mp4", type: "video/mp4" })),
    ).toMatch(/not a supported file type/);
  });

  it("allows an EMPTY declared type, because some browsers report none", () => {
    // A false negative here would block a valid upload; the server decides from
    // the bytes regardless.
    expect(describeUploadFileProblem(fakeFile({ type: "" }))).toBeNull();
  });

  it("allows application/octet-stream, which is what a generic picker reports", () => {
    expect(
      describeUploadFileProblem(fakeFile({ type: "application/octet-stream" })),
    ).toBeNull();
  });

  it("is advisory: a renamed .exe passes the extension check", () => {
    // This is the case the dialog's copy warns about. The client cannot detect
    // it — only the server's magic-byte sniff can — which is exactly why the UI
    // says the server checks the actual content.
    expect(
      describeUploadFileProblem(
        fakeFile({ name: "virus.mp3", type: "audio/mpeg" }),
      ),
    ).toBeNull();
  });
});

describe("audioUploadFormSchema", () => {
  it("requires a name", () => {
    expect(audioUploadFormSchema.safeParse({ name: "  " }).success).toBe(false);
  });

  it("caps the name at 150 — the DATABASE column width, not a @Size", () => {
    // VERIFIED: `audio_assets.name VARCHAR(150) NOT NULL`, and the upload
    // endpoint carries no `@Size`, so an over-long name would fail at the
    // database as a 500. The client enforces the schema instead.
    expect(AUDIO_NAME_MAX).toBe(150);
    expect(
      audioUploadFormSchema.safeParse({ name: "a".repeat(150) }).success,
    ).toBe(true);
    expect(
      audioUploadFormSchema.safeParse({ name: "a".repeat(151) }).success,
    ).toBe(false);
  });

  it("does not invent a description limit the server does not enforce", () => {
    // VERIFIED: the upload endpoint has no `@Valid` on its params and the column
    // is TEXT, so the description is genuinely unbounded. F3 applies only a
    // sanity cap and documents that.
    expect(AUDIO_DESCRIPTION_MAX).toBeGreaterThan(5000);
    expect(
      audioUploadFormSchema.safeParse({
        name: "x",
        description: "a".repeat(6000),
      }).success,
    ).toBe(true);
  });

  it("trims the name before it is sent", () => {
    const parsed = audioUploadFormSchema.parse({ name: "  Welcome  " });
    expect(parsed.name).toBe("Welcome");
  });
});

describe("updateAudioAssetSchema", () => {
  it("requires a name and caps it at 150", () => {
    // VERIFIED `UpdateAudioAssetRequest` is
    // `record (@NotBlank @Size(max=150) String name, @Size(max=5000) String description)`.
    expect(updateAudioAssetSchema.safeParse({ name: "" }).success).toBe(false);
    expect(updateAudioAssetSchema.safeParse({ name: "a".repeat(151) }).success).toBe(
      false,
    );
  });

  it("has exactly two fields, so no technical field can be edited", () => {
    // `fileName`, `contentType`, `fileSize`, `durationSeconds`, `checksum` and
    // `storageReference` are NOT in the update DTO. Offering them would be
    // offering a form that 400s.
    expect(Object.keys(updateAudioAssetSchema.shape).sort()).toEqual([
      "description",
      "name",
    ]);
  });

  it("omits a blank description so it clears server-side", () => {
    // VERIFIED `AudioAssetMapper` blank-to-nulls the description, and the
    // payload omits the key rather than sending "".
    const parsed = updateAudioAssetSchema.parse({ name: "A", description: "  " });
    expect(toUpdateAudioAssetPayload(parsed)).toEqual({
      name: "A",
      description: undefined,
    });
  });

  it("preserves a real description", () => {
    const parsed = updateAudioAssetSchema.parse({
      name: "A",
      description: "  Notes  ",
    });
    expect(toUpdateAudioAssetPayload(parsed).description).toBe("Notes");
  });
});
