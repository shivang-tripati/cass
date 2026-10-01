import { z } from "zod";

import type {
  CreateTtsTemplatePayload,
  TtsTemplateScope,
  TtsTemplateVariable,
  UpdateTtsTemplatePayload,
} from "@/lib/api/contracts";

/**
 * TTS template form schemas and a faithful port of the server-side contract
 * validator.
 *
 * VERIFIED (F3) against `tts/TtsTemplateValidation`,
 * `tts/TtsTemplateService.create` / `.update` and the
 * `tts/dto/{Create,Update}TtsTemplateRequest` records.
 *
 * ## F1 deferred the full rule set; F3 ports it
 *
 * F1's schema checked variable-name shape, duplicate names and a *heuristic*
 * stray-brace regex, and left placeholder/declaration agreement to the server.
 * That left a user who typed `{{fristName}}` waiting for a round trip to learn
 * that the variable was not declared.
 *
 * `TtsTemplateValidation` is a pure, deterministic function over a string and a
 * list — no database, no Spring, no I/O — so it ports exactly. The port below is
 * a line-for-line mirror, and `tts-template-mutation.test.ts` pins each rule,
 * including the ones that are easy to get subtly wrong:
 *
 *  - `{{ firstName }}` is VALID: the placeholder pattern allows inner whitespace
 *    and the name is compared trimmed.
 *  - A declared variable that is never referenced is **allowed**. There is no
 *    "unused variable" check, and the frontend must not add one — that would
 *    reject a template the server accepts.
 *  - An EMPTY variable list is valid and means "no placeholders are allowed".
 *    `variables` is `@NotNull @Size(max = 50)`, so an empty array must be sent,
 *    never omitted.
 *  - The stray-brace rule is stronger than a paired-brace check. The server
 *    removes every *valid* placeholder and then rejects the text if ANY `{` or
 *    `}` survives. So `{{{name}}` is an error, not a valid placeholder followed
 *    by literal text.
 *  - `templateText` is stored **verbatim** (`TtsTemplateMapper.applyCommon`
 *    calls `setTemplateText` with no trim), so F3 validates the trimmed value
 *    for blankness but sends the user's exact text.
 */

/** VERIFIED `TtsTemplateValidation.VARIABLE_NAME`. */
export const TTS_VARIABLE_NAME_REGEX = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/;

/** VERIFIED `TtsTemplateValidation.ALLOWED_TYPES`. */
export const TTS_VARIABLE_TYPES = [
  "STRING",
  "NUMBER",
  "BOOLEAN",
  "DATE",
] as const;

export type TtsVariableType = (typeof TTS_VARIABLE_TYPES)[number];

export const TTS_NAME_MAX = 150;
export const TTS_DESCRIPTION_MAX = 5000;
export const TTS_TEXT_MAX = 5000;
/** VERIFIED `@Size(max = 50)` on `variables`. */
export const TTS_VARIABLES_MAX = 50;

/**
 * `TtsTemplateValidation.PLACEHOLDER` = `\{\{\s*(.*?)\s*}}`.
 *
 * Java's `.` excludes line terminators, and that is meaningful here: a
 * placeholder split across a newline is NOT a valid placeholder, and the text
 * then fails the stray-brace check instead. The character class below excludes
 * exactly Java's line terminators so the two implementations agree rather than
 * approximately agreeing.
 */
const PLACEHOLDER = /\{\{\s*([^\n\r\u0085\u2028\u2029]*?)\s*\}\}/g;

/** VERIFIED `TtsTemplateValidation.VARIABLE_NAME`, applied to a trimmed name. */
function isValidVariableName(name: string): boolean {
  return TTS_VARIABLE_NAME_REGEX.test(name);
}

function isAllowedType(type: string): boolean {
  return (TTS_VARIABLE_TYPES as readonly string[]).includes(
    type.trim().toUpperCase(),
  );
}

/** One contract violation, addressed to a form field. */
export interface TtsContractIssue {
  /** Form path the message belongs on. `variables` is the array as a whole. */
  path: "templateText" | "variables";
  message: string;
}

/** One declared variable, as the form HOLDS it (pre-transform, so `type` is
 *  whatever the user picked from the select). */
export interface TtsTemplateVariableForm {
  name: string;
  type?: string | null;
  required?: boolean | null;
}

/**
 * Port of `TtsTemplateValidation.validateSchema` + `.validateTemplateText`.
 *
 * Returns the declared names, or `null` when the schema is absent/empty —
 * matching the Java, where `null` is the sentinel that makes every placeholder
 * undeclared.
 *
 * The parameters are deliberately loose (`{ name?: string | null; type?: string
 * | null }`) rather than the response or form types. This function validates
 * untrusted input, and it is called with both the response shape and the form's
 * pre-transform shape — typing it to either one would force a cast at one of the
 * two call sites.
 *
 * The order of checks matches the server: the whole schema is validated first,
 * then the text. So a template with both a bad variable name and an undeclared
 * placeholder reports the variable problem, exactly as the backend would.
 */
export function validateTemplateContract(
  templateText: string,
  variables: readonly { name?: string | null; type?: string | null }[],
): Set<string> | null {
  const issues: TtsContractIssue[] = [];
  const text = templateText ?? "";

  // --- validateSchema ---
  let declaredNames: Set<string> | null = null;
  if (variables.length > 0) {
    const names = new Set<string>();
    for (const variable of variables) {
      const name = (variable.name ?? "").trim();
      if (!isValidVariableName(name)) {
        issues.push({
          path: "variables",
          message: `Variable name "${name}" is invalid. Use 1-64 characters: letters, digits, underscore.`,
        });
        continue;
      }
      if (names.has(name)) {
        issues.push({
          path: "variables",
          message: `Duplicate variable declaration: ${name}`,
        });
        continue;
      }
      names.add(name);
      if (
        variable.type != null &&
        variable.type !== "" &&
        !isAllowedType(variable.type)
      ) {
        issues.push({
          path: "variables",
          message: `Variable "${name}" has unsupported type ${variable.type}. Allowed: STRING, NUMBER, BOOLEAN, DATE.`,
        });
      }
    }
    // The Java returns the set even when a duplicate was skipped, so a name that
    // was rejected is simply absent from it.
    declaredNames = names;
  }

  // --- validateTemplateText ---
  const referenced = new Set<string>();
  PLACEHOLDER.lastIndex = 0;
  for (const match of text.matchAll(PLACEHOLDER)) {
    const name = match[1] ?? "";
    if (!isValidVariableName(name)) {
      issues.push({
        path: "templateText",
        message: `Malformed placeholder {{${name}}}. Use simple names like {{firstName}}.`,
      });
      continue;
    }
    if (declaredNames == null || !declaredNames.has(name)) {
      issues.push({
        path: "templateText",
        message: `Template references undeclared variable {{${name}}}. Declare it in the variable schema.`,
      });
      continue;
    }
    referenced.add(name);
  }

  // Reject any brace the valid placeholders did not consume. This is the
  // server's rule verbatim, and it is stricter than "braces are balanced": a
  // placeholder-looking run that failed validation leaves braces behind and is
  // reported here as a second, complementary error.
  const withoutPlaceholders = text.replace(new RegExp(PLACEHOLDER.source, "g"), "");
  if (/[{}]/.test(withoutPlaceholders)) {
    issues.push({
      path: "templateText",
      message:
        "Template text contains stray braces. Only {{variableName}} placeholders are allowed.",
    });
  }

  if (issues.length > 0) {
    const error = new Error("TTS template contract validation failed");
    Object.assign(error, { issues });
    throw error as Error & { issues: TtsContractIssue[] };
  }

  // `referenced` is computed because the rule set is ported in full; the server
  // also ignores it (an unused declared variable is legal). Referencing it here
  // documents that the port is complete without inventing an extra rule.
  void referenced;
  return declaredNames;
}

/** Non-throwing form: the issues, or an empty array when the contract holds. */
export function templateContractIssues(
  templateText: string,
  variables: readonly { name?: string | null; type?: string | null }[],
): TtsContractIssue[] {
  try {
    validateTemplateContract(templateText, variables);
    return [];
  } catch (error) {
    if (
      error &&
      typeof error === "object" &&
      "issues" in error &&
      Array.isArray((error as { issues: unknown }).issues)
    ) {
      return (error as { issues: TtsContractIssue[] }).issues;
    }
    throw error;
  }
}

/* -------------------------------------------------------------------------- */
/*                              Form schemas                                  */
/* -------------------------------------------------------------------------- */

const variableSchema = z.object({
  name: z.string(),
  /**
   * `TtsTemplateValidation.ALLOWED_TYPES` is checked case-insensitively
   * (`type.trim().toUpperCase()`) but the value is then PERSISTED AS SENT —
   * `TtsTemplateMapper.applyCommon` stores the variable record verbatim. So the
   * form normalises to uppercase on the way in rather than relying on the
   * server to, which keeps the stored value canonical.
   *
   * An empty value becomes `undefined` and is sent as `null`: the backend skips
   * the type check when it is null (`validateSchema`), and the response carries
   * it back as null, so "no type" has to be representable.
   */
  type: z
    .string()
    .transform((value) => value.trim().toUpperCase() as TtsVariableType | "")
    .refine(
      (value) =>
        value === "" || (TTS_VARIABLE_TYPES as readonly string[]).includes(value),
      {
        message: `Type must be one of ${TTS_VARIABLE_TYPES.join(", ")}.`,
      },
    )
    .transform((value): TtsVariableType | undefined =>
      value === "" ? undefined : value,
    )
    .optional()
    .nullable(),
  required: z.boolean().optional().nullable(),
});

const baseFields = {
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(TTS_NAME_MAX, `Name must be at most ${TTS_NAME_MAX} characters.`),
  description: z.string().trim().max(TTS_DESCRIPTION_MAX).optional().nullable(),
  // `templateText` is stored VERBATIM, so the value is not trimmed — only the
  // blank check looks at the trimmed copy, mirroring `@NotBlank`.
  templateText: z
    .string()
    .refine((value) => value.trim().length > 0, "Template text is required.")
    .max(TTS_TEXT_MAX, `Template text must be at most ${TTS_TEXT_MAX} characters.`),
  variables: z
    .array(variableSchema)
    .max(
      TTS_VARIABLES_MAX,
      `At most ${TTS_VARIABLES_MAX} variables are allowed.`,
    ),
};

type BaseValues = z.infer<z.ZodObject<typeof baseFields>>;

/** Applies the ported contract to both create and edit, so a template cannot be
 *  saved by taking the other path — the defect F1 had already fixed for the
 *  subset of rules it had ported. */
function withContract(schema: z.ZodObject<typeof baseFields>) {
  return schema.superRefine((values: BaseValues, ctx: z.RefinementCtx) => {
    for (const issue of templateContractIssues(
      values.templateText,
      values.variables,
    )) {
      ctx.addIssue({
        code: "custom",
        path: [issue.path],
        message: issue.message,
      });
    }
  });
}

export const createTtsTemplateSchema = withContract(z.object(baseFields));
export const updateTtsTemplateSchema = withContract(z.object(baseFields));

/**
 * The validated OUTPUT — what the payload builders take.
 *
 * Distinct from the input because the variable `type` field is transformed: the
 * form holds a raw string from the select, and the schema normalises it to the
 * `TtsVariableType` union on the way out. React Hook Form models that with its
 * third generic, `useForm<Input, unknown, Output>`; without it the resolver's
 * output type does not line up with the declared field type. This is the same
 * boundary F2 hit on the contact `attributes` field.
 */
export type TtsTemplateValues = z.infer<typeof createTtsTemplateSchema>;
export type CreateTtsTemplateValues = TtsTemplateValues;
export type UpdateTtsTemplateValues = TtsTemplateValues;

/** What the form HOLDS, before validation transforms run. */
export type TtsTemplateFormInput = z.input<typeof createTtsTemplateSchema>;

/** The shape the shared variables editor is typed against. */
export type TtsTemplateFormValues = TtsTemplateFormInput;

/** What the create form collects beyond the shared fields. */
export interface TtsCreateScopeFields {
  scope: TtsTemplateScope;
  /** Only meaningful for a platform caller creating a TENANT-scoped system
   *  template. `null` otherwise. */
  tenantId: string | null;
}

/**
 * Build the create payload.
 *
 * VERIFIED `TtsTemplateService.create` L68-114 — the two rules this mirrors:
 *
 *  - a `GLOBAL` template must NOT carry a `tenantId` (400 otherwise), and is
 *    created `APPROVED`;
 *  - a `TENANT` template created by a platform caller MAY carry a `tenantId`,
 *    which must name an ACTIVE tenant, and the capability is then checked
 *    platform-wide.
 *
 * `tenantId` is omitted from the JSON body entirely when it is `null`, rather
 * than sent as `null`, so the GLOBAL branch can never be tripped by a stray
 * explicit null on a platform that distinguishes the two.
 */
export function toCreateTtsTemplatePayload(
  values: CreateTtsTemplateValues,
  scopeFields: TtsCreateScopeFields,
): CreateTtsTemplatePayload {
  const payload: CreateTtsTemplatePayload = {
    name: values.name,
    description: values.description || undefined,
    templateText: values.templateText,
    variables: toVariablePayload(values.variables),
    scope: scopeFields.scope,
  };
  if (scopeFields.scope === "TENANT" && scopeFields.tenantId) {
    payload.tenantId = scopeFields.tenantId;
  }
  return payload;
}

export function toUpdateTtsTemplatePayload(
  values: UpdateTtsTemplateValues,
): UpdateTtsTemplatePayload {
  // VERIFIED: `UpdateTtsTemplateRequest` is `record (name, description,
  // templateText, variables)` — no `tenantId`, no `scope`, no `status`. Scope is
  // immutable and status moves only through approve/reject.
  return {
    name: values.name,
    description: values.description || undefined,
    templateText: values.templateText,
    variables: toVariablePayload(values.variables),
  };
}

/** Maps validated form variables onto the API shape.
 *
 * Takes the OUTPUT variable type, where `type` has already been narrowed to the
 * `TtsVariableType` union by the schema, so the payload needs no cast.
 *
 * `type` is sent as an explicit `null` rather than omitted: the backend skips
 * the allowed-types check when it is null (`validateSchema`), and the response
 * carries it back as null, so "no type" must round-trip. `required` is always
 * sent explicitly rather than left undefined, so a stored variable never
 * depends on whether the client happened to include the key. */
function toVariablePayload(
  variables: TtsTemplateValues["variables"],
): TtsTemplateVariable[] {
  return variables.map((variable) => ({
    name: variable.name.trim(),
    type: variable.type ?? null,
    required: variable.required ?? false,
  }));
}

/** `variables` is `@NotNull`: an empty template sends `[]`, never `undefined`. */
export const EMPTY_TTS_VARIABLES: TtsTemplateVariable[] = [];

/**
 * True when an edit would actually change the template's CONTENT, and therefore
 * send an `APPROVED` template back to `PENDING_APPROVAL`.
 *
 * VERIFIED `TtsTemplateService.update` L181-186: the revert is gated on
 * `!templateText.equals(new)` OR the declared-variable NAME SET differing.
 * Consequences the pre-F3 dialog got wrong:
 *
 *  - renaming an approved template does **not** reset its approval;
 *  - editing only the description does **not** reset its approval;
 *  - reordering variables, or toggling a `required` flag or a variable's
 *    `type`, does **not** reset it either — only the text or the set of names.
 *
 * The dialog uses this to say exactly what will happen, instead of warning
 * unconditionally and being wrong most of the time.
 */
export function willTtsEditResetApproval(
  current: { templateText: string; variables: TtsTemplateVariable[] | null },
  next: { templateText: string; variables: TtsTemplateVariable[] },
): boolean {
  if (current.templateText !== next.templateText) return true;
  const currentNames = new Set(
    (current.variables ?? []).map((variable) => variable.name.trim()),
  );
  const nextNames = new Set(next.variables.map((variable) => variable.name.trim()));
  if (currentNames.size !== nextNames.size) return true;
  for (const name of nextNames) {
    if (!currentNames.has(name)) return true;
  }
  return false;
}
