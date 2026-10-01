import { describe, expect, it } from "vitest";

import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { toSignupFormFieldKey } from "@/components/auth/signup-error-mapping";

/**
 * F1 — server validation error mapping.
 *
 * The backend's `GlobalExceptionHandler` builds `errors[]` from the binding
 * result, so `field` is the DTO's own property name, and for a nested DTO it is
 * dotted (`admin.email`). A flat form must translate that, and must NOT apply an
 * error to a field it does not have.
 */

describe("toSignupFormFieldKey", () => {
  it("flattens a nested admin field onto the form's flat key", () => {
    expect(toSignupFormFieldKey("admin.email")).toBe("adminEmail");
    expect(toSignupFormFieldKey("admin.password")).toBe("adminPassword");
    expect(toSignupFormFieldKey("admin.displayName")).toBe("adminDisplayName");
  });

  it("leaves a top-level field untouched", () => {
    expect(toSignupFormFieldKey("name")).toBe("name");
    expect(toSignupFormFieldKey("slug")).toBe("slug");
  });
});

describe("applyServerFieldErrors", () => {
  const known = ["name", "slug", "adminEmail", "adminPassword"];

  it("applies a matching field and reports the count", () => {
    const applied: Array<[string, string]> = [];
    const count = applyServerFieldErrors(
      [
        { field: "name", code: "NotBlank", message: "must not be blank" },
        { field: "slug", code: "Pattern", message: "must be lowercase" },
      ],
      known,
      (field, message) => applied.push([field, message]),
    );
    expect(count).toBe(2);
    expect(applied).toEqual([
      ["name", "must not be blank"],
      ["slug", "must be lowercase"],
    ]);
  });

  it("drops a field the form does not declare", () => {
    // Attaching an error to an unknown field would either crash an
    // uncontrolled input or land on the wrong control.
    const applied: string[] = [];
    const count = applyServerFieldErrors(
      [
        { field: "surprise", code: "X", message: "nope" },
        { field: "name", code: "NotBlank", message: "required" },
      ],
      known,
      (field) => applied.push(field),
    );
    expect(count).toBe(1);
    expect(applied).toEqual(["name"]);
  });

  it("drops a non-simple field name, e.g. a deeper path", () => {
    const applied: string[] = [];
    const count = applyServerFieldErrors(
      [{ field: "a.b.c", code: "X", message: "nope" }],
      known,
      (field) => applied.push(field),
    );
    expect(count).toBe(0);
    expect(applied).toEqual([]);
  });

  it("applies a remapped nested field", () => {
    const applied: Array<[string, string]> = [];
    const count = applyServerFieldErrors(
      [{ field: "admin.email", code: "Email", message: "must be a well-formed email" }],
      known,
      (field, message) => applied.push([field, message]),
      toSignupFormFieldKey,
    );
    expect(count).toBe(1);
    expect(applied).toEqual([["adminEmail", "must be a well-formed email"]]);
  });

  it("returns 0 for absent or empty input", () => {
    const noop = () => {
      throw new Error("should not be called");
    };
    expect(applyServerFieldErrors(undefined, known, noop)).toBe(0);
    expect(applyServerFieldErrors([], known, noop)).toBe(0);
  });

  it("ignores a malformed entry with no field", () => {
    const applied: string[] = [];
    const count = applyServerFieldErrors(
      [
        { field: "", code: "X", message: "nope" },
        { field: "name", code: "NotBlank", message: "required" },
      ],
      known,
      (field) => applied.push(field),
    );
    expect(count).toBe(1);
    expect(applied).toEqual(["name"]);
  });
});
