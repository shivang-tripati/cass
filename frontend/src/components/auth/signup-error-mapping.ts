/**
 * Maps backend FieldError paths onto this module's flat form keys.
 *
 * The signup DTOs nest administrator inputs under `admin`
 * (`TenantAdminInput` / `AdminAccountInput` are a single `admin` component of
 * `CreateTenantRequest` / `CreateResellerRequest`), so Bean Validation reports a
 * violation on one of its members with a dotted path: `admin.email`,
 * `admin.password`, `admin.displayName`. The forms keep those inputs flat
 * (`adminEmail`, `adminPassword`, `adminDisplayName`), so the segment has to be
 * camel-cased on the way out.
 *
 * F1 — this was previously a plain string concat (`"admin" + field.slice(6)`),
 * which produced `adminemail` and `adminpassword`. Because the caller guards
 * with `if (key in schema.shape)`, the mismatch was swallowed: an invalid admin
 * email silently fell back to the summary alert instead of appearing under the
 * field the user was looking at.
 */
export function toSignupFormFieldKey(field: string): string {
  const prefix = "admin.";
  if (!field.startsWith(prefix)) return field;

  const member = field.slice(prefix.length);
  if (member.length === 0) return field;

  return `admin${member.charAt(0).toUpperCase()}${member.slice(1)}`;
}
