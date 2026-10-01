import { describe, expect, it } from "vitest";

import {
  CONTACT_E164_REGEX,
  canonicalizePhoneNumber,
  createContactSchema,
  formatAttributesForInput,
  toCreateContactPayload,
  toUpdateContactPayload,
  updateContactSchema,
} from "@/lib/schemas/contact-mutation";

/**
 * F2 — contact form contract.
 *
 * VERIFIED against `contact/ContactValidation`, `contact/dto/CreateContactRequest`
 * and `contact/dto/UpdateContactRequest`.
 *
 * The reason this file exists in F2: the previous schema typed `attributes` as
 * `z.record(z.string(), z.unknown())` while the form bound a `<TextareaField>`,
 * so a textarea STRING was validated against a record schema. The field could
 * never pass. The contract is now "form holds JSON text, schema parses it", and
 * these tests pin the parsing — the part that silently produced a wrong payload
 * before.
 */

describe("canonicalizePhoneNumber", () => {
  it("mirrors ContactValidation.canonicalizePhoneNumber for separators", () => {
    // VERIFIED: PHONE_SEPARATORS = [\s().\-] are stripped before the E.164 test.
    expect(canonicalizePhoneNumber("+91 (801) 234-5678")).toBe("+918012345678");
    expect(canonicalizePhoneNumber("  +918012345678  ")).toBe("+918012345678");
    expect(canonicalizePhoneNumber("+1.800.555.0199")).toBe("+18005550199");
  });

  it("returns null for anything that cannot reach canonical E.164 form", () => {
    expect(canonicalizePhoneNumber("")).toBeNull();
    expect(canonicalizePhoneNumber("08012345678")).toBeNull();
    expect(canonicalizePhoneNumber("+0123456789")).toBeNull();
    expect(canonicalizePhoneNumber("+12345")).toBeNull();
    expect(canonicalizePhoneNumber("+1234567890123456")).toBeNull();
    expect(canonicalizePhoneNumber("not a phone")).toBeNull();
  });

  it("exposes the same regex the backend compiles", () => {
    // ContactValidation.E164_REGEX = ^\+[1-9][0-9]{6,14}$
    expect(CONTACT_E164_REGEX.source).toBe("^\\+[1-9][0-9]{6,14}$");
  });
});

describe("createContactSchema", () => {
  it("requires a phone number", () => {
    const result = createContactSchema.safeParse({ phoneNumber: "" });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe("Phone number is required.");
    }
  });

  it("canonicalizes a formatted phone number to the value that gets persisted", () => {
    const result = createContactSchema.safeParse({
      phoneNumber: "+91 (801) 234-5678",
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.phoneNumber).toBe("+918012345678");
    }
  });

  it("rejects a non-E.164 number with the backend's own message", () => {
    const result = createContactSchema.safeParse({ phoneNumber: "08012345678" });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe(
        "Must be a valid E.164 number, e.g. +918012345678.",
      );
    }
  });

  it("trims names and drops them when blank", () => {
    const result = createContactSchema.safeParse({
      firstName: "  John  ",
      lastName: "   ",
      phoneNumber: "+918012345678",
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.firstName).toBe("John");
      // `ContactMapper.applyCommon` stores firstName as trim() WITHOUT
      // blank-to-null, so an empty string would be persisted as "". The schema
      // must therefore omit the field instead of sending "".
      expect(result.data.lastName).toBeUndefined();
    }
  });

  it("enforces the backend @Size(max = 100) on names", () => {
    const result = createContactSchema.safeParse({
      firstName: "a".repeat(101),
      phoneNumber: "+918012345678",
    });
    expect(result.success).toBe(false);
  });

  it("enforces the backend @Size(max = 255) and @Email on email", () => {
    expect(
      createContactSchema.safeParse({
        phoneNumber: "+918012345678",
        email: "a".repeat(250) + "@example.com",
      }).success,
    ).toBe(false);
    expect(
      createContactSchema.safeParse({
        phoneNumber: "+918012345678",
        email: "not-an-email",
      }).success,
    ).toBe(false);
  });

  it("treats a blank email as absent so it clears rather than fails", () => {
    const result = createContactSchema.safeParse({
      phoneNumber: "+918012345678",
      email: "   ",
    });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.email).toBeUndefined();
  });
});

describe("attributes (F2 fix)", () => {
  it("parses a JSON object string into the object the API takes", () => {
    // The API field is a JsonNode OBJECT. A textarea produces a string, so the
    // schema is the boundary that converts one into the other.
    const result = createContactSchema.safeParse({
      phoneNumber: "+918012345678",
      attributes: '{"city": "Pune", "tier": 2}',
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.attributes).toEqual({ city: "Pune", tier: 2 });
    }
  });

  it("maps a blank value to undefined so an empty object is never sent", () => {
    const result = createContactSchema.safeParse({
      phoneNumber: "+918012345678",
      attributes: "   ",
    });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.attributes).toBeUndefined();
  });

  it("rejects malformed JSON", () => {
    const result = createContactSchema.safeParse({
      phoneNumber: "+918012345678",
      attributes: "{city: Pune}",
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe("Attributes must be valid JSON.");
    }
  });

  it("rejects a JSON array — the backend import path requires an object", () => {
    // VERIFIED: `ContactGroupService.parseAttributes` rejects a non-object node
    // with MALFORMED_ATTRIBUTES, and the column is a jsonb object.
    const result = createContactSchema.safeParse({
      phoneNumber: "+918012345678",
      attributes: '["a", "b"]',
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toContain("JSON object");
    }
  });

  it("rejects a bare JSON scalar", () => {
    expect(
      createContactSchema.safeParse({
        phoneNumber: "+918012345678",
        attributes: '"just a string"',
      }).success,
    ).toBe(false);
    expect(
      createContactSchema.safeParse({
        phoneNumber: "+918012345678",
        attributes: "42",
      }).success,
    ).toBe(false);
  });
});

describe("payload builders", () => {
  it("omits absent fields rather than sending nulls", () => {
    const parsed = createContactSchema.parse({ phoneNumber: "+918012345678" });
    expect(toCreateContactPayload(parsed)).toEqual({
      phoneNumber: "+918012345678",
      firstName: undefined,
      lastName: undefined,
      email: undefined,
      attributes: undefined,
    });
  });

  it("sends the parsed attributes object, not the raw textarea string", () => {
    const parsed = createContactSchema.parse({
      phoneNumber: "+918012345678",
      attributes: '{"city":"Pune"}',
    });
    const payload = toCreateContactPayload(parsed);
    expect(payload.attributes).toEqual({ city: "Pune" });
    expect(typeof payload.attributes).toBe("object");
  });

  it("produces the same field set for update as for create", () => {
    // VERIFIED: CreateContactRequest and UpdateContactRequest are structurally
    // identical, so sharing one schema is faithful, not a shortcut.
    expect(Object.keys(updateContactSchema.shape).sort()).toEqual(
      Object.keys(createContactSchema.shape).sort(),
    );
    const parsed = updateContactSchema.parse({
      phoneNumber: "+918012345678",
      attributes: '{"a":1}',
    });
    expect(toUpdateContactPayload(parsed)).toEqual(toCreateContactPayload(parsed));
  });
});

describe("formatAttributesForInput", () => {
  it("renders an object as indented JSON for the textarea", () => {
    expect(formatAttributesForInput({ city: "Pune" })).toBe('{\n  "city": "Pune"\n}');
  });

  it("returns an empty string for absent or empty attributes", () => {
    expect(formatAttributesForInput(null)).toBe("");
    expect(formatAttributesForInput(undefined)).toBe("");
    expect(formatAttributesForInput({})).toBe("");
  });

  it("round-trips through the schema without losing data", () => {
    const original = { city: "Pune", tier: 2, tags: ["a", "b"], nested: { ok: true } };
    const parsed = createContactSchema.parse({
      phoneNumber: "+918012345678",
      attributes: formatAttributesForInput(original),
    });
    expect(parsed.attributes).toEqual(original);
  });

  it("never throws on a value JSON.stringify cannot serialise", () => {
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(formatAttributesForInput(cyclic)).toBe("");
  });
});
