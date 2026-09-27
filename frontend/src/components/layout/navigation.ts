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
import { Capability } from "@/lib/auth/capabilities";

/**
 * Primary platform navigation with capability-based visibility.
 * Each item declares the capability required to display it.
 * Backend remains the authorization authority - this is UX only.
 */
export interface NavItem {
  title: string;
  url: string;
  icon: LucideIcon;
  requiredCapabilities?: Capability[];
  /** Optional scope requirement (platform/reseller/tenant) for coarse filtering. */
  requiredScope?: "platform" | "reseller" | "tenant";
}

export const PLATFORM_NAV_ITEMS: readonly NavItem[] = [
  { title: "Users", url: "/users", icon: UsersIcon, requiredCapabilities: [Capability.USER_VIEW] },
  { title: "Tenants", url: "/tenants", icon: BuildingIcon, requiredCapabilities: [Capability.TENANT_VIEW], requiredScope: "platform" },
  { title: "Resellers", url: "/resellers", icon: StoreIcon, requiredCapabilities: [Capability.RESELLER_VIEW], requiredScope: "platform" },
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

/**
 * Filters navigation items based on the user's capabilities and scope.
 */
export function getVisibleNavItems(user: import("@/lib/api/contracts").AuthenticatedUserResponse | null | undefined): NavItem[] {
  if (!user) return [];
  
  return PLATFORM_NAV_ITEMS.filter((item) => {
    // Check capability requirement
    if (item.requiredCapabilities && item.requiredCapabilities.length > 0) {
      const hasCap = item.requiredCapabilities.some((cap) => user.capabilities?.includes(cap));
      if (!hasCap) return false;
    }
    
    // Check scope requirement
    if (item.requiredScope) {
      if (item.requiredScope === "platform" && user.homeType !== null) {
        // Platform-only items (Tenants, Resellers) only visible to platform users
        return false;
      }
      if (item.requiredScope === "reseller" && user.homeType !== "RESELLER") {
        return false;
      }
      if (item.requiredScope === "tenant" && user.homeType !== "TENANT") {
        return false;
      }
    }
    
    return true;
  });
}
