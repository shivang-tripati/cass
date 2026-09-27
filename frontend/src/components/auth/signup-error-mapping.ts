/**
 * Maps backend FieldError paths onto this module's flat form keys.
 * The signup DTOs nest administrator inputs under `admin`, while the
 * forms keep them flat (`adminEmail`, `adminPassword`, …).
 */
export function toSignupFormFieldKey(field: string): string {
  return field.startsWith("admin.") ? `admin${field.slice("admin.".length)}` : field;
}
