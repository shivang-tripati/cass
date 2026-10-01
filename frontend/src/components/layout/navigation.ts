import {
  BuildingIcon,
  MegaphoneIcon,
  MicIcon,
  MusicIcon,
  PhoneIcon,
  StoreIcon,
  UserIcon,
  UsersIcon,
  Users2Icon,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import { Capability, hasAnyCapability } from "@/lib/auth/capabilities";
import type { OperatingScope } from "@/lib/auth/operating-context";

/**
 * Primary navigation with capability-based visibility.
 *
 * Each item declares the capability required to display it. Navigation hiding is
 * NOT authorization — it only avoids offering a link that would 403. The
 * backend authorises every request independently.
 *
 * F1: the scope test now reads the same derived scope as the rest of the app
 * (`OperatingScope`) instead of comparing `homeType` against three separate
 * literals. `homeType === null` still means platform scope, because
 * `OrganizationalHomeType` has no `PLATFORM` constant.
 */
export interface NavItem {
  title: string;
  url: string;
  icon: LucideIcon;
  requiredCapabilities?: Capability[];
  /** Optional scope requirement for coarse filtering. */
  requiredScope?: OperatingScope;
}

export const PLATFORM_NAV_ITEMS: readonly NavItem[] = [
  { title: "Users", url: "/users", icon: UsersIcon, requiredCapabilities: [Capability.USER_VIEW] },
  { title: "Tenants", url: "/tenants", icon: BuildingIcon, requiredCapabilities: [Capability.TENANT_VIEW], requiredScope: "PLATFORM" },
  { title: "Resellers", url: "/resellers", icon: StoreIcon, requiredCapabilities: [Capability.RESELLER_VIEW], requiredScope: "PLATFORM" },
  { title: "DIDs", url: "/dids", icon: PhoneIcon, requiredCapabilities: [Capability.DID_VIEW] },
  { title: "Campaigns", url: "/campaigns", icon: MegaphoneIcon, requiredCapabilities: [Capability.CAMPAIGN_VIEW] },
  { title: "Contact Groups", url: "/contact-groups", icon: Users2Icon, requiredCapabilities: [Capability.CONTACT_VIEW] },
  { title: "Audio Assets", url: "/audio-assets", icon: MusicIcon, requiredCapabilities: [Capability.AUDIO_VIEW] },
  { title: "TTS Templates", url: "/tts-templates", icon: MicIcon, requiredCapabilities: [Capability.TTS_VIEW] },
  { title: "Account", url: "/account", icon: UserIcon },
] as const;

export function findNavItem(pathname: string): NavItem | undefined {
  return [...PLATFORM_NAV_ITEMS]
    .sort((a, b) => b.url.length - a.url.length)
    .find((item) => pathname === item.url || pathname.startsWith(`${item.url}/`));
}

/** Maps a `/me` payload onto the operating scope.
 *
 * Exported so navigation and `useOperatingContext` cannot drift apart; the
 * derivation itself lives in `@/lib/auth/operating-context`. */
export function scopeOf(
  user: AuthenticatedUserResponse | null | undefined,
): OperatingScope | null {
  if (!user) return null;
  if (user.homeType === "TENANT") return "TENANT";
  if (user.homeType === "RESELLER") return "RESELLER";
  return "PLATFORM";
}

/** Filters navigation items by capability AND, when declared, by scope. */
export function getVisibleNavItems(
  user: AuthenticatedUserResponse | null | undefined,
): NavItem[] {
  if (!user) return [];

  const scope = scopeOf(user);

  return PLATFORM_NAV_ITEMS.filter((item) => {
    if (
      item.requiredCapabilities &&
      item.requiredCapabilities.length > 0 &&
      !hasAnyCapability(user, item.requiredCapabilities)
    ) {
      return false;
    }
    if (item.requiredScope && item.requiredScope !== scope) {
      return false;
    }
    return true;
  });
}
