import { describe, expect, it } from "vitest";

import {
  createContactGroupSchema,
  toCreateContactGroupPayload,
  toUpdateContactGroupPayload,
  updateContactGroupSchema,
} from "@/lib/schemas/contact-group-mutation";

/**
 * F2 — contact group form contract.
 *
 * VERIFIED against `contact/dto/CreateContactGroupRequest` and
 * `contact/dto/UpdateContactGroupRequest`:
 *
 *   record CreateContactGroupRequest(@NotBlank @Size(max=150) String name,
 *                                   @Size(max=5000) String description)
 *
 * The two records are structurally identical, and there is no `tenantId` and no
 * `memberCount` on either. A tenant picker therefore has nothing to bind to, and
 * a "member count" input has nothing to submit — both would be invented fields.
 */
describe("createContactGroupSchema", () => {
  it("requires a name", () => {
    const result = createContactGroupSchema.safeParse({ name: "   " });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe("Name is required.");
    }
  });

  it("enforces the backend @Size(max = 150) on name", () => {
    expect(
      createContactGroupSchema.safeParse({ name: "a".repeat(151) }).success,
    ).toBe(false);
    expect(
      createContactGroupSchema.safeParse({ name: "a".repeat(150) }).success,
    ).toBe(true);
  });

  it("enforces the backend @Size(max = 5000) on description", () => {
    expect(
      createContactGroupSchema.safeParse({
        name: "September list",
        description: "a".repeat(5001),
      }).success,
    ).toBe(false);
  });

  it("accepts an absent description", () => {
    const result = createContactGroupSchema.safeParse({ name: "September list" });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.description).toBeUndefined();
  });

  it("has no tenantId and no memberCount field", () => {
    // VERIFIED: neither record declares them. A form field bound to either would
    // be sent to a DTO that does not accept it.
    expect(Object.keys(createContactGroupSchema.shape).sort()).toEqual([
      "description",
      "name",
    ]);
  });
});

describe("payload builders", () => {
  it("omits a blank description instead of sending an empty string", () => {
    // VERIFIED: `ContactGroupMapper.applyCommon` assigns description verbatim
    // and unconditionally, so an omitted field clears it — and sending "" would
    // persist "" rather than null.
    const parsed = createContactGroupSchema.parse({ name: "Leads", description: "  " });
    expect(toCreateContactGroupPayload(parsed)).toEqual({
      name: "Leads",
      description: undefined,
    });
  });

  it("preserves a real description", () => {
    const parsed = createContactGroupSchema.parse({
      name: "Leads",
      description: "  From the September trade show  ",
    });
    expect(toCreateContactGroupPayload(parsed).description).toBe(
      "From the September trade show",
    );
  });

  it("trims the name, which the backend stores verbatim", () => {
    // `entity.setName(name)` — no trim on the server, so the client normalises.
    const parsed = createContactGroupSchema.parse({ name: "  Leads  " });
    expect(toCreateContactGroupPayload(parsed).name).toBe("Leads");
  });

  it("produces the same payload for update as for create", () => {
    const created = createContactGroupSchema.parse({ name: "Leads", description: "d" });
    const updated = updateContactGroupSchema.parse({ name: "Leads", description: "d" });
    expect(toUpdateContactGroupPayload(updated)).toEqual(
      toCreateContactGroupPayload(created),
    );
  });
});
