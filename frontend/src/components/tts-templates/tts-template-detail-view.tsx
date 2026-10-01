"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, BracesIcon, GlobeIcon, VariableIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { QueryErrorState } from "@/components/common/query-state";
import { ApprovalStatusBadge } from "@/components/common/approval-status-badge";
import { TtsTemplateScopeBadge } from "@/components/tts-templates/tts-template-scope-badge";
import { TtsTemplateActions } from "@/components/tts-templates/tts-template-actions";
import { useCan } from "@/lib/auth/use-can";
import { useOperatingContext } from "@/lib/auth/operating-context";
import {
  canApproveTtsTemplate,
  canManageTtsTemplate,
} from "@/lib/auth/content-gates";
import { getTtsTemplate, ttsTemplatesKeys } from "@/lib/api/tts-templates";
import { formatDateTime } from "@/lib/format";
import type { TtsTemplateVariable } from "@/lib/api/contracts";

/**
 * One TTS template.
 *
 * ## Scope is stated in words, not just a badge
 *
 * This is the page where the F3 requirement bites hardest. The pre-F3 view
 * never rendered `scope` at all — it showed a name, a status badge, the text,
 * the variables, and a raw `tenantId` UUID that is `null` for a global template,
 * so a global template rendered a blank "Tenant:" line. A user could not tell
 * what they were looking at, could not tell whether they could change it, and
 * could not tell that deleting it would affect every other organization.
 *
 * VERIFIED `TtsTemplateService`, the two scopes differ at every step:
 *
 *  - GLOBAL: `tenantId` is null (DB-enforced by
 *    `ck_tts_templates_scope_global_no_tenant`), platform-owned, approved on
 *    creation, readable by everyone, and **every write needs platform scope**.
 *  - TENANT: owned by one organization, pending approval on creation, readable
 *    by that organization (and a reseller managing it), and writes need the
 *    tenant capability.
 *
 * So the page states the ownership in a sentence, shows the scope badge, and
 * only renders the actions the caller can actually perform on *this* row.
 *
 * ## Errors
 *
 * The pre-F3 view rendered every failure as "Not found or no permission." F3
 * uses the shared `QueryErrorState`, whose 404 wording already matches the
 * backend's 404-cloaking: `findVisible` reports a foreign-tenant row and a
 * missing row identically, and it also reports a *non-approved global* row as
 * not-found to a tenant caller. The message says "does not exist, or it is
 * outside your organization" rather than claiming the template is gone.
 */
export function TtsTemplateDetailView({ id }: { id: string }) {
  const query = useQuery({
    queryKey: ttsTemplatesKeys.detail(id),
    queryFn: () => getTtsTemplate(id),
  });

  const { user } = useCan();
  const { scope: operatingScope } = useOperatingContext();

  if (query.isPending) {
    return (
      <div
        className="mx-auto flex w-full max-w-4xl items-center justify-center p-12"
        aria-busy="true"
      >
        <Spinner className="size-6" />
        <span className="sr-only">Loading template…</span>
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-4 p-6">
        <QueryErrorState
          error={query.error}
          entityLabel="this TTS template"
          onRetry={() => void query.refetch()}
        />
        <Button variant="ghost" asChild>
          <Link href="/tts-templates">
            <ArrowLeftIcon aria-hidden="true" />
            Back to TTS templates
          </Link>
        </Button>
      </div>
    );
  }

  const template = query.data;
  const isGlobal = template.scope === "GLOBAL";
  const canManage = canManageTtsTemplate(user, operatingScope, template);
  const canApprove = canApproveTtsTemplate(user, operatingScope, template);
  const variables: TtsTemplateVariable[] = template.variables ?? [];

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div className="space-y-3">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/tts-templates">
            <ArrowLeftIcon aria-hidden="true" />
            TTS templates
          </Link>
        </Button>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="space-y-2">
            <h1 className="text-2xl font-semibold tracking-tight">
              {template.name}
            </h1>
            <div className="flex flex-wrap items-center gap-2">
              <TtsTemplateScopeBadge scope={template.scope} />
              <ApprovalStatusBadge status={template.status} />
            </div>
          </div>
          <TtsTemplateActions
            template={template}
            canManage={canManage}
            canApprove={canApprove}
          />
        </div>
      </div>

      {template.description ? (
        <p className="text-sm text-muted-foreground">{template.description}</p>
      ) : null}

      {/* Ownership, in words. The badge is a compact signal; this is the part a
          user can act on. */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            {isGlobal ? (
              <GlobeIcon className="h-4 w-4" aria-hidden="true" />
            ) : (
              <BracesIcon className="h-4 w-4" aria-hidden="true" />
            )}
            Ownership
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {isGlobal ? (
            <p className="text-muted-foreground">
              This is a <strong>global</strong> template. It is owned by the
              platform, belongs to no single organization, and is available to
              every organization while it is approved. Changing or deleting it
              affects everyone, so only a platform administrator can.
            </p>
          ) : (
            <p className="text-muted-foreground">
              This is a <strong>tenant</strong> template. It belongs to your
              organization and is visible to the people who administer it.
              {canManage
                ? null
                : " Your account can view it but not change it."}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Template text</CardTitle>
        </CardHeader>
        <CardContent>
          {/* `whitespace-pre-wrap` preserves the author's line breaks, and the
              text is rendered verbatim — `templateText` is stored untrimmed. */}
          <pre className="overflow-x-auto whitespace-pre-wrap rounded-md bg-muted p-3 text-sm">
            {template.templateText}
          </pre>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <VariableIcon className="h-4 w-4" aria-hidden="true" />
            Variables ({variables.length})
          </CardTitle>
        </CardHeader>
        <CardContent>
          {variables.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No variables are declared, so the text above must not contain any{" "}
              <code>{"{{placeholders}}"}</code>.
            </p>
          ) : (
            <ul className="space-y-1 text-sm">
              {variables.map((variable) => (
                <li key={variable.name} className="flex flex-wrap items-center gap-2">
                  <code className="rounded bg-muted px-1.5 py-0.5 text-xs">
                    {variable.name}
                  </code>
                  <span className="text-muted-foreground">
                    {variable.type ?? "STRING"}
                  </span>
                  {variable.required ? (
                    <span className="text-muted-foreground">
                      · required
                    </span>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>History</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="space-y-3">
            <Row label="Created">{formatDateTime(template.createdAt)}</Row>
            <Row label="Last updated">
              {template.updatedAt ? formatDateTime(template.updatedAt) : <Dash />}
            </Row>
            <Row label="Template ID">
              <code className="break-all text-xs">{template.id}</code>
            </Row>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-sm sm:text-right">{children}</dd>
    </div>
  );
}

function Dash() {
  return <span className="text-muted-foreground">—</span>;
}
