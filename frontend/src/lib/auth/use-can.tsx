"use client";

import { createElement, type ReactNode } from "react";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import {
  Capability,
  hasAllCapabilities,
  hasAnyCapability,
} from "@/lib/auth/capabilities";
import { useCurrentUser } from "@/lib/auth/operating-context";

/**
 * The ONE capability check used by route pages, components and dialogs.
 *
 * Centralised deliberately: F0 found capability logic in `navigation.ts` and in
 * ad-hoc view bodies, with 12 declared capabilities never checked anywhere. All
 * of it now resolves through `can()` / `<Can>`, so an authorization decision is
 * made in exactly one place and can be tested without a DOM.
 *
 * This is UX PROTECTION ONLY. The backend authorises every request
 * independently (AuthorizationService.requireCapability, fail-closed). Hiding a
 * button prevents a guaranteed 403; it is never an access control. The backend
 * remains the security boundary.
 */

export interface CanOptions {
  /** Grant access when ANY of these is held. */
  any?: readonly Capability[];
  /** Require ALL of these. Combined with `any` using OR when both are supplied. */
  all?: readonly Capability[];
  /**
   * Hide (default) or disable. `disable` renders the child with
   * `aria-disabled` and `pointer-events: none`, which is better when the user
   * should see that a control exists but is not permitted to use it.
   */
  fallback?: "hide" | "disable";
}

/** Imperative form: `if (can("CONTACT_MANAGE")) { ... }`. */
export function can(
  user: AuthenticatedUserResponse | null | undefined,
  ...capabilities: readonly Capability[]
): boolean {
  return hasAnyCapability(user, capabilities);
}

/** Requires every listed capability. */
export function canAll(
  user: AuthenticatedUserResponse | null | undefined,
  ...capabilities: readonly Capability[]
): boolean {
  return hasAllCapabilities(user, capabilities);
}

/**
 * Hook form, for use inside components that already need the session.
 *
 * Returns a callable so a component can gate several unrelated actions without
 * repeating the hook, plus a ready-made `gate` for JSX.
 */
export function useCan() {
  const user = useCurrentUser();

  const test = (...capabilities: readonly Capability[]) =>
    hasAnyCapability(user, capabilities);
  const testAll = (...capabilities: readonly Capability[]) =>
    hasAllCapabilities(user, capabilities);

  return {
    user,
    can: test,
    canAll: testAll,
    /** `<Gate can={["AUDIO_MANAGE"]}>…</Gate>` */
    Gate: (props: {
      any?: readonly Capability[];
      all?: readonly Capability[];
      fallback?: "hide" | "disable";
      children: ReactNode;
    }) => gateWith(user, props),
  };
}

function evaluate(
  user: AuthenticatedUserResponse | null | undefined,
  options: CanOptions,
): boolean {
  if (options.all && options.all.length > 0) {
    return hasAllCapabilities(user, options.all);
  }
  if (options.any && options.any.length > 0) {
    return hasAnyCapability(user, options.any);
  }
  return true;
}

function gateWith(
  user: AuthenticatedUserResponse | null | undefined,
  options: CanOptions & { children: ReactNode },
): ReactNode {
  if (evaluate(user, options)) {
    return options.children;
  }
  if (options.fallback === "disable") {
    return createElement(
      "div",
      { "aria-disabled": true, style: { pointerEvents: "none", opacity: 0.5 } },
      options.children,
    );
  }
  return null;
}

/** Declarative form.
 *
 *   <Can any={[Capability.AUDIO_APPROVE]}>
 *     <Button onClick={approve}>Approve</Button>
 *   </Can>
 *
 * Defaults to hiding, which is the right default for a control that cannot
 * work. */
export function Can(
  props: CanOptions & { children: ReactNode },
): ReactNode {
  return gateWith(useCurrentUser(), props);
}
