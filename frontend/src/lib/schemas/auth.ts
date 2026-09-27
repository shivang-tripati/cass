import { z } from "zod";

/**
 * Client-side form schemas mirroring the auth DTO constraints.
 * Backend validation remains authoritative; these exist for UX only.
 */

/** POST /api/v1/auth/login — LoginRequest (NotBlank fields only). */
export const signInSchema = z.object({
  email: z.email("Enter a valid email address.").max(255),
  password: z.string().min(1, "Password is required."),
});
export type SignInValues = z.infer<typeof signInSchema>;

/** Password rules from PasswordPolicy (length-only, NIST-aligned). */
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 128;

const newPassword = z
  .string()
  .min(
    PASSWORD_MIN,
    `New password must be at least ${PASSWORD_MIN} characters.`,
  )
  .max(PASSWORD_MAX, `New password must be at most ${PASSWORD_MAX} characters.`);

/** POST /api/v1/auth/change-password — ChangePasswordRequest + confirm UX. */
export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, "Current password is required."),
    newPassword,
    confirmPassword: z.string().min(1, "Confirm the new password."),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match.",
  })
  .refine((v) => v.newPassword !== v.currentPassword, {
    path: ["newPassword"],
    message: "New password must differ from the current password.",
  });
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;
