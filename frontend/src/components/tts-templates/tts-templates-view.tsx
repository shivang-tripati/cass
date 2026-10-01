"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { MicIcon, PlusIcon, SearchXIcon, XIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { TablePagination } from "@/components/common/table-pagination";
import { EmptyState, QueryErrorState } from "@/components/common/query-state";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  TTS_TEMPLATE_SORTABLE_FIELDS,
  TTS_TEMPLATE_STATUSES,
  getTtsTemplates,
  ttsTemplatesKeys,
  type TtsTemplateListParams,
  type TtsTemplateSortField,
} from "@/lib/api/tts-templates";
import type { TtsTemplateResponse, TtsTemplateStatus } from "@/lib/api/contracts";
import { TtsTemplateTable, type TtsSort } from "@/components/tts-templates/tts-template-table";
import { CreateTtsTemplateDialog } from "@/components/tts-templates/create-tts-template-dialog";
import { useCan } from "@/lib/auth/use-can";
import { useOperatingContext } from "@/lib/auth/operating-context";
import {
  canApproveTtsTemplate,
  canManageTtsTemplate,
  canPerformTtsAction,
  ttsCreateOptionsFor,
} from "@/lib/auth/content-gates";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;
const ALL_STATUSES = "__all__";

const STATUS_LABELS: Record<TtsTemplateStatus, string> = {
  PENDING_APPROVAL: "Pending approval",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

const LIST_CONFIG = {
  sortableFields: TTS_TEMPLATE_SORTABLE_FIELDS,
  // VERIFIED `TtsTemplateService.DEFAULT_SORT` = `createdAt` DESC.
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50] as const,
  basePath: "/tts-templates" as const,
};

/**
 * TTS templates list.
 *
 * ## This list mixes two differently-owned resources, and says so
 *
 * VERIFIED `TtsTemplateService.list` returns, for a tenant caller, its OWN rows
 * of any status **plus** the approved `GLOBAL` catalog; for a reseller, its
 * hierarchy's rows plus the same catalog. So one page routinely contains both
 * kinds, and the `scope` column plus the card copy make that explicit instead of
 * implying a single kind of template.
 *
 * There is deliberately **no scope filter**, because the endpoint has no `scope`
 * parameter and filtering one page of a merged, paginated result would hide rows
 * that exist on other pages.
 *
 * ## Authorization
 *
 *  - **Create** is offered only when `ttsCreateOptionsFor` reports at least one
 *    creatable scope. That is a capability AND an operating-scope decision, not
 *    a bare `TTS_MANAGE` check — see the create dialog for the full matrix. A
 *    reseller administrator holds `TTS_MANAGE` but has no creatable scope, so
 *    they see no Create button rather than a form that 400s on submit.
 *  - **Per-row actions** are resolved inside `TtsTemplateActions` from the row's
 *    scope, because a `GLOBAL` row needs platform scope for every write. The
 *    view passes resolvers rather than booleans so the rule stays in one place.
 *
 * ## Status filter
 *
 * A real server parameter; an unknown value is a 400, so the select offers only
 * the three real enum names. It lives in component state for the same reason as
 * the audio one — `useUrlListState`'s `status` slot is hard-coded to the users
 * domain's `ACTIVE`/`SUSPENDED` lifecycle. Changing it resets to page 0, which
 * the pre-F3 code did not do.
 */
export function TtsTemplatesView() {
  const { state, patch } = useUrlListState(LIST_CONFIG);
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);
  const [status, setStatus] = useState<TtsTemplateStatus | "">("");
  const [createOpen, setCreateOpen] = useState(false);

  const { user } = useCan();
  const { scope: operatingScope } = useOperatingContext();
  const createOptions = ttsCreateOptionsFor(user, operatingScope);
  const canCreate = createOptions.scopes.length > 0;
  const canWrite = canPerformTtsAction(user, "write");

  const apiParams: TtsTemplateListParams = {
    page: state.page,
    size: state.size,
    sortField: state.sort.field as TtsTemplateSortField,
    sortDirection: state.sort.direction,
    status: status || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: ttsTemplatesKeys.list(apiParams),
    queryFn: () => getTtsTemplates(apiParams),
    placeholderData: (previous) => previous,
  });

  const filtersActive = state.q !== "" || status !== "";
  const clearFilters = useCallback(() => {
    setStatus("");
    patch({ q: "" }, true);
  }, [patch]);

  const onStatusChange = useCallback(
    (next: string) => {
      setStatus(next === ALL_STATUSES ? "" : (next as TtsTemplateStatus));
      patch({ page: 0 }, false);
    },
    [patch],
  );

  const resolveCanManage = useCallback(
    (template: TtsTemplateResponse) =>
      canManageTtsTemplate(user, operatingScope, template),
    [user, operatingScope],
  );

  const resolveCanApprove = useCallback(
    (template: TtsTemplateResponse) =>
      canApproveTtsTemplate(user, operatingScope, template),
    [user, operatingScope],
  );

  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="TTS Templates"
        description="Reusable text with {{variable}} placeholders, used to speak personalised audio during a call."
      >
        {canCreate ? (
          <Button onClick={() => setCreateOpen(true)}>
            <PlusIcon aria-hidden="true" />
            Create template
          </Button>
        ) : null}
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>TTS Templates</CardTitle>
          <CardDescription>
            This list mixes templates that belong to your organization with global
            templates shared by the platform. Global templates can only be changed
            by a platform administrator.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1 sm:max-w-xs">
              <Label htmlFor="tts-templates-search" className="sr-only">
                Search templates by name or template text
              </Label>
              <Input
                id="tts-templates-search"
                type="search"
                value={state.q}
                onChange={(event) => patch({ q: event.target.value }, true)}
                placeholder="Search name or text…"
                aria-label="Search templates by name or template text"
                className="pr-8"
              />
              {state.q ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Clear search"
                  className="absolute right-1 top-1/2 -translate-y-1/2"
                  onClick={() => patch({ q: "" }, true)}
                >
                  <XIcon aria-hidden="true" />
                </Button>
              ) : null}
            </div>

            <div className="flex items-center gap-2">
              <Label
                htmlFor="tts-templates-status"
                className="whitespace-nowrap text-sm text-muted-foreground"
              >
                Status
              </Label>
              <Select value={status || ALL_STATUSES} onValueChange={onStatusChange}>
                <SelectTrigger id="tts-templates-status" className="w-48">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_STATUSES}>All statuses</SelectItem>
                  {TTS_TEMPLATE_STATUSES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {STATUS_LABELS[value]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {filtersActive ? (
              <Button variant="ghost" onClick={clearFilters}>
                Clear filters
              </Button>
            ) : null}
          </div>

          {listQuery.isError ? (
            <QueryErrorState
              error={listQuery.error}
              entityLabel="TTS templates"
              onRetry={() => void listQuery.refetch()}
            />
          ) : null}

          {listQuery.isPending ? (
            <TableSkeleton columns={7} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <EmptyState
              icon={filtersActive ? SearchXIcon : MicIcon}
              title={
                filtersActive
                  ? "No templates match your filters"
                  : "No templates yet"
              }
              description={
                filtersActive
                  ? "Search matches the template name and its text. Try a different term, or clear the status filter."
                  : canCreate
                    ? "Create a template to define the text that is spoken during a call. It will need approval before campaigns can use it."
                    : "Templates appear here once they exist for your organization or in the shared global catalog."
              }
              action={
                filtersActive ? (
                  <Button variant="outline" size="sm" onClick={clearFilters}>
                    Clear filters
                  </Button>
                ) : undefined
              }
            />
          ) : listQuery.data ? (
            <>
              <div className="overflow-x-auto">
                <div
                  data-pending={listQuery.isFetching || undefined}
                  className="transition-opacity data-[pending=true]:opacity-60"
                >
                  <TtsTemplateTable
                    templates={listQuery.data.items}
                    sort={state.sort as TtsSort}
                    onSortChange={(sort) => patch({ sort }, false)}
                    canManage={resolveCanManage}
                    canApprove={resolveCanApprove}
                  />
                </div>
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="templates"
                  onPageSizeChange={(size) => patch({ size }, true)}
                  onPageChange={(page) => patch({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      {canWrite ? (
        <p className="mt-4 text-sm text-muted-foreground">
          {canCreate
            ? null
            : "You can review and approve templates, but creating a new one is not available for your account."}
        </p>
      ) : null}

      {canCreate ? (
        <CreateTtsTemplateDialog
          open={createOpen}
          onOpenChange={setCreateOpen}
        />
      ) : null}
    </div>
  );
}
