import type { FieldErrorDto } from "@/lib/api/types";

/**
 * F1 — shared mapping from a backend validation failure onto form fields.
 *
 * The backend emits `errors[]` as `{ field, code, message }` where `field` is
 * the DTO property path. GlobalExceptionHandler builds it from
 * `getBindingResult().getFieldErrors()`, so `field` is the DTO's own field name
 * (or, for a constraint violation, the last path segment — see `lastSegment`).
 * The entries are therefore flat names like `email`, `name`, `slug`, and are
 * NOT prefixed with a JSON path.
 *
 * Two consequences this module handles once, for every form:
 *
 *  1. NESTED DTO fields arrive dotted. `TenantSignupRequest` embeds `admin`, so
 *     its violations are reported as `admin.email` / `admin.password`. A flat
 *     signup form must map those onto its own flat keys. That mapping stays in
 *     `toSignupFormFieldKey` (signup-error-mapping.ts) because it is specific to
 *     the signup forms' shape; it is not a general rule.
 *
 *  2. UNKNOWN FIELDS must be dropped, not rendered. Applying a server message to
 *     a field the form does not have would either crash an uncontrolled input or
 *     attach the error to the wrong control, so anything that does not match a
 *     declared field is discarded here and the summary message is used instead.
 */

/** True when `field` is a plain property name, i.e. safe to hand to a form. */
function isSimpleFieldName(field: string): boolean {
  return /^[A-Za-z][A-Za-z0-9_]*$/.test(field);
}

/**
 * Filters `fieldErrors` down to those a form can actually display, and reports
 * how many were applied.
 *
 * @param fieldErrors the backend `errors[]`
 * @param knownFields the field keys the form declares
 * @param remap       optional per-form key translation (e.g. dotted → flat)
 * @param apply       callback that receives (formFieldKey, message)
 */
export function applyServerFieldErrors(
  fieldErrors: readonly FieldErrorDto[] | undefined,
  knownFields: readonly string[],
  apply: (field: string, message: string) => void,
  remap?: (field: string) => string,
): number {
  if (!fieldErrors || fieldErrors.length === 0) return 0;

  const known = new Set(knownFields);
  let applied = 0;

  for (const error of fieldErrors) {
    if (typeof error.field !== "string" || error.field.length === 0) continue;
    const remapped = remap ? remap(error.field) : error.field;
    if (!isSimpleFieldName(remapped)) continue;
    if (!known.has(remapped)) continue;
    apply(remapped, error.message);
    applied += 1;
  }

  return applied;
}
