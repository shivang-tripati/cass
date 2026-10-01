/**
 * Authorization entry points.
 *
 * Import capability checks from here, not from the individual modules, so that
 * "how do we ask what a user may do" has exactly one answer:
 *
 *   import { Capability, can, Can, useCan } from "@/lib/auth";
 *   import { useOperatingContext } from "@/lib/auth/operating-context";
 *
 * `operating-context` is intentionally NOT re-exported from this barrel: it
 * pulls in the session query, and keeping it a separate import makes the
 * capability layer (pure, testable, no React session dependency) obvious.
 */
export * from "./capabilities";
export * from "./use-can";
