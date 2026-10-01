import { Badge } from "@/components/ui/badge";
import { GlobeIcon, BuildingIcon } from "lucide-react";

import type { TtsTemplateScope } from "@/lib/api/contracts";

/**
 * Scope badge for a TTS template.
 *
 * ## Why this is a first-class visual, not a footnote
 *
 * VERIFIED `TtsTemplateScope` is a genuine ownership boundary, not a label.
 * `TtsTemplateService` treats the two scopes differently at every step:
 *
 *  | | GLOBAL | TENANT |
 *  |---|---|---|
 *  | `tenantId` | `null` — DB-enforced by `ck_tts_templates_scope_global_no_tenant` | the owning tenant — DB-enforced by `ck_tts_templates_scope_tenant_requires_tenant` |
 *  | created by | platform callers only | the owning tenant, or a platform caller seeding one |
 *  | created in | `APPROVED` | `PENDING_APPROVAL` |
 *  | visible to | every caller, once approved | the owning tenant (plus a reseller's hierarchy) |
 *  | edit / delete / approve | requires platform scope | requires `TTS_MANAGE` / `TTS_APPROVE` on the tenant |
 *
 * A user who cannot tell which is which will try to edit a shared platform
 * template from a tenant account and get a 403 they cannot interpret. So the
 * scope is shown on every row and on the detail page, with the ownership
 * consequence in the accessible title.
 *
 * ## Accessibility
 *
 * The word — "Global" or "Tenant" — is always rendered, alongside an icon that
 * is `aria-hidden`. Scope is never conveyed by colour or shape alone.
 */
const META: Record<
  TtsTemplateScope,
  {
    label: string;
    className: string;
    title: string;
    Icon: typeof GlobeIcon;
  }
> = {
  GLOBAL: {
    label: "Global",
    Icon: GlobeIcon,
    className:
      "border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-400",
    title:
      "Global template: owned by the platform, shared with every organization, and editable only by a platform administrator.",
  },
  TENANT: {
    label: "Tenant",
    Icon: BuildingIcon,
    className:
      "border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-400",
    title:
      "Tenant template: owned by one organization. Only that organization, or a reseller administrator managing it, can change it.",
  },
};

export function TtsTemplateScopeBadge({
  scope,
}: {
  scope: TtsTemplateScope;
}) {
  const meta = META[scope] ?? META.TENANT;
  const { Icon } = meta;
  return (
    <Badge
      variant="outline"
      title={meta.title}
      className={meta.className}
      data-scope={scope}
    >
      <Icon aria-hidden="true" className="size-3" />
      {meta.label}
    </Badge>
  );
}
