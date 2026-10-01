import { describe, expect, it } from "vitest";

import {
  createTtsTemplateSchema,
  templateContractIssues,
  toCreateTtsTemplatePayload,
  toUpdateTtsTemplatePayload,
  updateTtsTemplateSchema,
  validateTemplateContract,
  willTtsEditResetApproval,
  TTS_VARIABLE_NAME_REGEX,
} from "@/lib/schemas/tts-template-mutation";
import type { TtsTemplateVariable } from "@/lib/api/contracts";

/**
 * F3 — a faithful port of `TtsTemplateValidation`.
 *
 * VERIFIED against `backend/.../tts/TtsTemplateValidation.java`. That class is
 * pure and deterministic — a string and a list in, a `Set<String>` or an
 * `IllegalArgumentException` out — so it ports exactly, and the port is worth
 * pinning rule by rule. F1 ported only a subset and used a heuristic stray-brace
 * regex; these tests are the difference between "the server will tell you
 * eventually" and "the form tells you while you type".
 *
 * Each test names the Java it mirrors. The subtle ones:
 *
 *  - inner whitespace in a placeholder is allowed and trimmed;
 *  - a DECLARED-BUT-UNUSED variable is legal (there is no such check);
 *  - an EMPTY variable list is legal and forbids all placeholders;
 *  - the stray-brace rule strips VALID placeholders first, so `{{{name}}` fails
 *    even though it "looks like" a placeholder plus literal text;
 *  - a type is compared case-insensitively, and the stored value is verbatim.
 */
describe("VARIABLE_NAME — TtsTemplateValidation.VARIABLE_NAME", () => {
  it("is the same expression the server compiles", () => {
    expect(TTS_VARIABLE_NAME_REGEX.source).toBe("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");
  });

  it("accepts a leading letter then letters, digits and underscores", () => {
    for (const name of ["a", "firstName", "Balance_2", "x".repeat(64)]) {
      expect(validateTemplateContract("hi", [{ name }])).not.toBeNull();
    }
  });

  it("rejects a leading digit, a leading underscore, a dash, a space and an empty name", () => {
    for (const name of ["1abc", "_abc", "first-name", "first name", ""]) {
      const issues = templateContractIssues("hi", [{ name }]);
      expect(
        issues.some((issue) => issue.path === "variables"),
        `expected "${name}" to be rejected`,
      ).toBe(true);
    }
  });

  it("TRIMS a name before validating, so surrounding whitespace is legal", () => {
    // VERIFIED: `String name = variable.name() == null ? "" : variable.name().trim();`
    // runs before `VARIABLE_NAME.matcher(name).matches()`. Rejecting " x" would
    // be a false negative against a server that accepts it.
    expect(templateContractIssues("hi", [{ name: " x " }])).toEqual([]);
  });

  it("rejects a name longer than 64 characters", () => {
    expect(templateContractIssues("hi", [{ name: "x".repeat(65) }])).not.toEqual([]);
  });
});

describe("duplicate declarations — validateSchema", () => {
  it("rejects two variables with the same name", () => {
    const issues = templateContractIssues("hi", [
      { name: "firstName" },
      { name: "firstName" },
    ]);
    expect(issues).toHaveLength(1);
    expect(issues[0]?.path).toBe("variables");
    expect(issues[0]?.message).toContain("Duplicate variable declaration");
  });

  it("compares names AFTER trimming, so ' x' and 'x' collide", () => {
    // VERIFIED: `String name = variable.name() == null ? "" : variable.name().trim();`
    const issues = templateContractIssues("hi", [{ name: " x" }, { name: "x" }]);
    expect(issues.some((i) => i.message.includes("Duplicate"))).toBe(true);
  });

  it("allows different names", () => {
    expect(templateContractIssues("hi", [{ name: "a" }, { name: "b" }])).toEqual([]);
  });
});

describe("variable types — ALLOWED_TYPES", () => {
  it("accepts the four declared types", () => {
    for (const type of ["STRING", "NUMBER", "BOOLEAN", "DATE"]) {
      expect(templateContractIssues("hi", [{ name: "x", type }])).toEqual([]);
    }
  });

  it("accepts them case-insensitively, as the server does", () => {
    // VERIFIED: `ALLOWED_TYPES.contains(type.trim().toUpperCase(Locale.ROOT))`.
    for (const type of ["string", "number", "Boolean", " date "]) {
      expect(templateContractIssues("hi", [{ name: "x", type }])).toEqual([]);
    }
  });

  it("rejects any other type and names the allowed set", () => {
    const issues = templateContractIssues("hi", [{ name: "x", type: "JSON" }]);
    expect(issues[0]?.path).toBe("variables");
    expect(issues[0]?.message).toContain("unsupported type");
    expect(issues[0]?.message).toContain("STRING, NUMBER, BOOLEAN, DATE");
  });

  it("treats a null or absent type as unconstrained, as the server does", () => {
    // VERIFIED: `if (type != null && !ALLOWED_TYPES.contains(...))`.
    expect(templateContractIssues("hi", [{ name: "x", type: null }])).toEqual([]);
    expect(templateContractIssues("hi", [{ name: "x" }])).toEqual([]);
  });
});

describe("placeholders — validateTemplateText", () => {
  it("accepts a declared placeholder", () => {
    expect(
      templateContractIssues("Hello {{firstName}}", [{ name: "firstName" }]),
    ).toEqual([]);
  });

  it("accepts inner whitespace and trims it before matching", () => {
    // VERIFIED: PLACEHOLDER = `\{\{\s*(.*?)\s*}}`, so `{{ firstName }}` captures
    // `firstName`.
    expect(
      templateContractIssues("Hello {{  firstName  }}", [
        { name: "firstName" },
      ]),
    ).toEqual([]);
  });

  it("rejects a placeholder whose name is not declared", () => {
    const issues = templateContractIssues("Hello {{fristName}}", [
      { name: "firstName" },
    ]);
    expect(issues).toHaveLength(1);
    expect(issues[0]?.path).toBe("templateText");
    expect(issues[0]?.message).toContain("undeclared variable");
    expect(issues[0]?.message).toContain("fristName");
  });

  it("rejects EVERY placeholder when the variable list is empty", () => {
    // VERIFIED: `validateSchema` returns null for an empty list, and
    // `declaredNames == null` makes every placeholder undeclared.
    const issues = templateContractIssues("Hello {{firstName}}", []);
    expect(issues.some((i) => i.message.includes("undeclared"))).toBe(true);
  });

  it("reports the SCHEMA problem first, matching the server's check order", () => {
    // `{{1st}}` with a variable declared as `1st` is doubly invalid: the name
    // fails VARIABLE_NAME, and the placeholder is therefore malformed too.
    // VERIFIED order: `validateSchema` runs to completion before
    // `validateTemplateText`, so the array issue is reported first — and the
    // port produces the same ordering.
    const issues = templateContractIssues("{{1st}}", [{ name: "1st" }]);
    expect(issues.length).toBeGreaterThan(0);
    expect(issues[0]?.path).toBe("variables");
    expect(issues[0]?.message).toContain("is invalid");
  });

  it("still flags the malformed placeholder in the text", () => {
    const issues = templateContractIssues("{{1st}}", [{ name: "1st" }]);
    expect(
      issues.some(
        (issue) =>
          issue.path === "templateText" &&
          issue.message.includes("Malformed placeholder"),
      ),
    ).toBe(true);
  });

  it("accepts text with no placeholders at all", () => {
    expect(templateContractIssues("Hello there.", [{ name: "unused" }])).toEqual(
      [],
    );
  });
});

describe("a DECLARED-BUT-UNUSED variable is legal", () => {
  it("is accepted — there is no unused-variable check on the server", () => {
    // This is the rule most likely to be "helpfully" added to a client, and
    // adding it would reject a template the backend stores without complaint.
    expect(
      templateContractIssues("Hello there.", [
        { name: "firstName" },
        { name: "neverUsed" },
      ]),
    ).toEqual([]);
  });
});

describe("stray braces — STRAY_BRACE after removing valid placeholders", () => {
  it("rejects a single opening brace", () => {
    const issues = templateContractIssues("Hello {name}", []);
    expect(issues.some((i) => i.message.includes("stray braces"))).toBe(true);
  });

  it("rejects a lone closing brace", () => {
    const issues = templateContractIssues("Hello name}", []);
    expect(issues.some((i) => i.message.includes("stray braces"))).toBe(true);
  });

  it("rejects an unbalanced pair", () => {
    expect(
      templateContractIssues("Hello {{firstName}", [{ name: "firstName" }]).some(
        (i) => i.message.includes("stray braces"),
      ),
    ).toBe(true);
  });

  it("rejects THREE opening braces, because the leftover brace survives", () => {
    // VERIFIED: the server removes every VALID `{{...}}` match and then looks
    // for any remaining brace. The pre-F3 heuristic regex missed this case
    // entirely, because every `{` was preceded by another `{`.
    const issues = templateContractIssues("{{{firstName}}}", [
      { name: "firstName" },
    ]);
    expect(issues.some((i) => i.message.includes("stray braces"))).toBe(true);
  });

  it("accepts a well-formed text with several placeholders", () => {
    expect(
      templateContractIssues(
        "Hi {{firstName}}, your balance is {{balance}} on {{date}}.",
        [{ name: "firstName" }, { name: "balance" }, { name: "date" }],
      ),
    ).toEqual([]);
  });
});

describe("validateTemplateContract returns the declared names", () => {
  it("returns null for an empty schema, matching the Java sentinel", () => {
    // VERIFIED: `validateSchema` returns null when the list is null or empty,
    // and that null is what makes every placeholder undeclared.
    expect(validateTemplateContract("hi", [])).toBeNull();
  });

  it("returns the trimmed declared names otherwise", () => {
    expect(validateTemplateContract("hi", [{ name: " a " }, { name: "b" }])).toEqual(
      new Set(["a", "b"]),
    );
  });
});

describe("the two schemas share ONE refinement", () => {
  const invalid = {
    name: "Greeting",
    description: "",
    templateText: "Hello {{undeclared}}",
    variables: [{ name: "declared", type: "STRING", required: false }],
  };

  it("blocks the same contract violation on create", () => {
    const result = createTtsTemplateSchema.safeParse(invalid);
    expect(result.success).toBe(false);
  });

  it("blocks it on edit too", () => {
    // F1 already fixed the duplicate/stray subset for both paths. F3 keeps them
    // on one `withContract` wrapper so the two cannot drift again.
    const result = updateTtsTemplateSchema.safeParse(invalid);
    expect(result.success).toBe(false);
  });

  it("accepts a valid template on both paths", () => {
    const valid = {
      name: "Greeting",
      description: "Opening line",
      templateText: "Hello {{firstName}}",
      variables: [{ name: "firstName", type: "STRING", required: true }],
    };
    expect(createTtsTemplateSchema.safeParse(valid).success).toBe(true);
    expect(updateTtsTemplateSchema.safeParse(valid).success).toBe(true);
  });

  it("rejects an empty name and an over-long name", () => {
    const base = { templateText: "hi", variables: [] };
    expect(
      createTtsTemplateSchema.safeParse({ ...base, name: "   " }).success,
    ).toBe(false);
    expect(
      createTtsTemplateSchema.safeParse({ ...base, name: "a".repeat(151) })
        .success,
    ).toBe(false);
  });

  it("rejects an over-long template text, matching @Size(max = 5000)", () => {
    expect(
      createTtsTemplateSchema.safeParse({
        name: "x",
        templateText: "a".repeat(5001),
        variables: [],
      }).success,
    ).toBe(false);
  });

  it("rejects more than 50 variables, matching @Size(max = 50)", () => {
    const variables = Array.from({ length: 51 }, (_, i) => ({
      name: `v${i}`,
      type: "STRING",
      required: false,
    }));
    const result = createTtsTemplateSchema.safeParse({
      name: "x",
      templateText: "hi",
      variables,
    });
    expect(result.success).toBe(false);
  });

  it("rejects a blank template text, mirroring @NotBlank", () => {
    expect(
      createTtsTemplateSchema.safeParse({
        name: "x",
        templateText: "   ",
        variables: [],
      }).success,
    ).toBe(false);
  });

  it("preserves template text verbatim rather than trimming it", () => {
    // VERIFIED: `TtsTemplateMapper.applyCommon` calls `setTemplateText` with no
    // trim, so leading and trailing whitespace the author typed is stored and
    // spoken. Trimming it client-side would silently change the audio.
    const parsed = createTtsTemplateSchema.parse({
      name: "x",
      templateText: "  Hello  ",
      variables: [],
    });
    expect(parsed.templateText).toBe("  Hello  ");
  });
});

describe("payload builders", () => {
  const base = {
    name: "Greeting",
    description: "",
    templateText: "Hello {{firstName}}",
    variables: [{ name: " firstName ", type: "STRING" as const, required: true }],
  };

  it("trims a variable name and normalises an absent type to null", () => {
    const values = createTtsTemplateSchema.parse({
      ...base,
      variables: [{ name: " firstName " }],
    });
    const payload = toUpdateTtsTemplatePayload(values);
    expect(payload.variables).toEqual([
      { name: "firstName", type: null, required: false },
    ]);
  });

  it("sends an empty variables ARRAY for a template with none, never undefined", () => {
    // VERIFIED: `variables` is `@NotNull`, so omitting the key is a 400.
    const values = createTtsTemplateSchema.parse({
      name: "x",
      templateText: "Plain text",
      variables: [],
    });
    const payload = toUpdateTtsTemplatePayload(values);
    expect(payload.variables).toEqual([]);
  });

  it("omits a blank description rather than sending an empty string", () => {
    const values = createTtsTemplateSchema.parse({ ...base, description: "  " });
    expect(toUpdateTtsTemplatePayload(values).description).toBeUndefined();
  });

  it("omits scope and tenantId from an UPDATE payload", () => {
    // VERIFIED: `UpdateTtsTemplateRequest` is
    // `record (name, description, templateText, variables)`. Scope is immutable
    // and status moves only through approve/reject.
    const values = updateTtsTemplateSchema.parse(base);
    const payload = toUpdateTtsTemplatePayload(values);
    expect(Object.keys(payload).sort()).toEqual([
      "description",
      "name",
      "templateText",
      "variables",
    ]);
  });
});

describe("create payload and the GLOBAL rule", () => {
  const values = createTtsTemplateSchema.parse({
    name: "Greeting",
    templateText: "Hello",
    variables: [],
  });

  it("omits tenantId entirely for a GLOBAL template", () => {
    // VERIFIED `TtsTemplateService.create` L82-84: a GLOBAL template with a
    // `tenantId` is a 400 "GLOBAL templates are platform-owned and must not
    // reference a tenant." Omitting the key — rather than sending null — means
    // the check can never be tripped by a stray value.
    const payload = toCreateTtsTemplatePayload(values, {
      scope: "GLOBAL",
      tenantId: "some-tenant-id",
    });
    expect(payload.scope).toBe("GLOBAL");
    expect("tenantId" in payload).toBe(false);
  });

  it("includes tenantId for a platform-seeded TENANT template", () => {
    const payload = toCreateTtsTemplatePayload(values, {
      scope: "TENANT",
      tenantId: "t-42",
    });
    expect(payload.scope).toBe("TENANT");
    expect(payload.tenantId).toBe("t-42");
  });

  it("omits tenantId for an ordinary tenant template", () => {
    const payload = toCreateTtsTemplatePayload(values, {
      scope: "TENANT",
      tenantId: null,
    });
    expect("tenantId" in payload).toBe(false);
  });
});

/** A variable literal, typed so `type` does not widen to `string` in an object
 *  literal passed straight to a typed parameter. */
function variable(
  name: string,
  type: "STRING" | "NUMBER" | "BOOLEAN" | "DATE" = "STRING",
  required = false,
): TtsTemplateVariable {
  return { name, type, required };
}

describe("willTtsEditResetApproval — the L181-186 re-approval rule", () => {
  const current = {
    templateText: "Hello {{firstName}}",
    variables: [variable("firstName", "STRING", true)],
  };

  it("is true when the text changes", () => {
    expect(
      willTtsEditResetApproval(current, {
        templateText: "Hi {{firstName}}",
        variables: current.variables,
      }),
    ).toBe(true);
  });

  it("is true when a variable NAME is added", () => {
    expect(
      willTtsEditResetApproval(current, {
        templateText: current.templateText,
        variables: [...current.variables, variable("balance", "NUMBER")],
      }),
    ).toBe(true);
  });

  it("is true when a variable NAME is removed", () => {
    expect(
      willTtsEditResetApproval(current, {
        templateText: current.templateText,
        variables: [],
      }),
    ).toBe(true);
  });

  it("is FALSE for a rename or description-only edit", () => {
    // This is the case the pre-F3 dialog warned about unconditionally, and the
    // warning was wrong. Name and description are not part of `contentChanged`.
    expect(
      willTtsEditResetApproval(current, {
        templateText: current.templateText,
        variables: current.variables,
      }),
    ).toBe(false);
  });

  it("is FALSE for a type change or a required-flag flip", () => {
    // VERIFIED: `toSchemaSet` collects only the NAMES, so neither affects the
    // comparison.
    expect(
      willTtsEditResetApproval(current, {
        templateText: current.templateText,
        variables: [variable("firstName", "DATE", false)],
      }),
    ).toBe(false);
  });

  it("is FALSE for a reordering of the same names", () => {
    expect(
      willTtsEditResetApproval(
        {
          templateText: "hi",
          variables: [variable("a"), variable("b")],
        },
        {
          templateText: "hi",
          variables: [variable("b"), variable("a")],
        },
      ),
    ).toBe(false);
  });

  it("treats a null variables array as an empty schema", () => {
    expect(
      willTtsEditResetApproval(
        { templateText: "hi", variables: null },
        { templateText: "hi", variables: [] },
      ),
    ).toBe(false);
  });
});
