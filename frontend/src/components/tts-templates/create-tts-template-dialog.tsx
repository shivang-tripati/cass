"use client";

import { useState } from "react";
import { useForm, FormProvider } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  FieldDescription,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryErrorState } from "@/components/common/query-state";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { TtsTemplateVariablesField } from "@/components/tts-templates/tts-template-variables-field";
import { TtsTemplateScopeBadge } from "@/components/tts-templates/tts-template-scope-badge";
import { createTtsTemplate, ttsTemplatesKeys } from "@/lib/api/tts-templates";
import { getTenants, tenantsKeys } from "@/lib/api/tenants";
import { toApiError } from "@/lib/api/error";
import { useCan } from "@/lib/auth/use-can";
import { useOperatingContext } from "@/lib/auth/operating-context";
import { ttsCreateOptionsFor } from "@/lib/auth/content-gates";
import type { LifecycleStatus } from "@/lib/api/types";
import type { TtsTemplateScope } from "@/lib/api/contracts";
import {
  createTtsTemplateSchema,
  toCreateTtsTemplatePayload,
  type CreateTtsTemplateValues,
  type TtsTemplateFormInput,
} from "@/lib/schemas/tts-template-mutation";

const NO_TENANT = "__none__";

/**
 * Create a TTS template.
 *
 * ## The scope control, and why it is not just a decoration
 *
 * F3's central addition. F1's `toCreatePayload` hard-coded `scope: "TENANT"`,
 * which made `TtsTemplateScope.GLOBAL` unreachable from the UI even though the
 * type, the enum and a dedicated database constraint all existed.
 *
 * Which scopes are OFFERED is decided by `ttsCreateOptionsFor`, which mirrors
 * `TtsTemplateService.create` L68-114:
 *
 * | Caller | Offered | Why |
 * |---|---|---|
 * | TENANT context + `TTS_MANAGE` | `TENANT` only | the service uses the caller's own tenant (case 2) |
 * | **PLATFORM** context + `TTS_MANAGE` | `TENANT` (with a target) and `GLOBAL` | cases 1 and 3, both checked `AccessCheck.platformWide()` |
 * | RESELLER context + `TTS_MANAGE` | **none** | the service has no reseller branch; a RESELLER-scoped assignment never covers `platformWide()`, so any create is a 400 or 403 |
 * | no `TTS_MANAGE` | none | — |
 *
 * The reseller row is the one worth stating plainly: V20 grants `RESELLER_ADMIN`
 * all three `TTS_*` keys, but the create endpoint has no path a reseller can
 * take, so a reseller sees **no** create button. Offering a form there would
 * produce "A tenant must be specified for this operation." on every submit.
 *
 * ## The tenant picker is not a security boundary
 *
 * When a platform caller chooses `TENANT`, the form requires a target tenant —
 * because that is the only way to seed a system template into someone else's
 * organization. The options come from `GET /tenants`, which returns **all**
 * tenants for a platform caller and is filtered to the caller's own tenant or
 * hierarchy for anyone else (`TenantService.list`). So the list can only ever
 * contain tenants the backend already considers in scope.
 *
 * It is also filtered to `ACTIVE`, because the service refuses an inactive
 * target with "Referenced tenant does not exist or is not usable."
 *
 * And it is only ever shown to a platform caller, because only they can use it.
 * No `tenantId` is read from a URL, and none is editable as free text.
 *
 * ## `GLOBAL` never carries a tenant
 *
 * The service rejects `scope: "GLOBAL"` with a non-null `tenantId` (400). The
 * payload builder omits `tenantId` entirely for `GLOBAL` rather than sending
 * `null`, so a stray value cannot trip that check.
 */
export function CreateTtsTemplateDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const { user } = useCan();
  const { scope: operatingScope } = useOperatingContext();
  const createOptions = ttsCreateOptionsFor(user, operatingScope);

  const [scope, setScope] = useState<TtsTemplateScope>("TENANT");
  const [targetTenantId, setTargetTenantId] = useState<string>(NO_TENANT);
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<TtsTemplateFormInput, unknown, CreateTtsTemplateValues>({
    // The third generic is required: the variable `type` select value is a raw
    // string in the form and a `TtsVariableType` union after validation, and
    // React Hook Form needs both types to type the resolver.
    resolver: zodResolver(createTtsTemplateSchema),
    defaultValues: {
      name: "",
      description: "",
      templateText: "",
      variables: [],
    },
  });

  const effectiveScope: TtsTemplateScope = createOptions.scopes.includes(scope)
    ? scope
    : (createOptions.scopes[0] ?? "TENANT");

  // Only a platform caller ever sees this, and only for a TENANT-scoped
  // template. `enabled` keeps the query dormant for everyone else.
  const needsTenant = effectiveScope === "TENANT" && createOptions.requiresTargetTenant;

  const tenantsQuery = useQuery({
    queryKey: tenantsKeys.list({
      page: 0,
      size: 100,
      sortField: "name",
      sortDirection: "asc",
    }),
    queryFn: () =>
      getTenants({
        page: 0,
        size: 100,
        sortField: "name",
        sortDirection: "asc",
      }),
    enabled: open && needsTenant,
  });

  const activeTenants = (tenantsQuery.data?.items ?? []).filter(
    (tenant) => tenant.status === ("ACTIVE" as LifecycleStatus),
  );

  function handleOpenChange(next: boolean) {
    if (form.formState.isSubmitting) return;
    if (!next) {
      form.reset();
      setScope("TENANT");
      setTargetTenantId(NO_TENANT);
      setAlert(null);
    }
    onOpenChange(next);
  }

  const onSubmit = async (values: CreateTtsTemplateValues) => {
    setAlert(null);
    if (needsTenant && targetTenantId === NO_TENANT) {
      setAlert("Choose the organization this template belongs to.");
      return;
    }
    try {
      const created = await createTtsTemplate(
        toCreateTtsTemplatePayload(values, {
          scope: effectiveScope,
          tenantId: needsTenant ? targetTenantId : null,
        }),
      );
      await queryClient.invalidateQueries({ queryKey: ttsTemplatesKeys.all });
      toast.success("Template created", {
        description:
          created.scope === "GLOBAL"
            ? `${created.name} is a global template and is available to every organization.`
            : created.status === "APPROVED"
              ? `${created.name} was created as an approved system template.`
              : `${created.name} is waiting for approval before campaigns can use it.`,
      });
      handleOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      const mapped = applyServerFieldErrors(
        apiError.fieldErrors,
        Object.keys(createTtsTemplateSchema.shape),
        (field, message) => {
          form.setError(field as keyof CreateTtsTemplateValues, { message });
        },
      );
      if (mapped > 0 && apiError.status === 400) return;
      setAlert(apiError.message);
    }
  };

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex max-h-[88vh] max-w-2xl flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>Create template</DialogTitle>
          <DialogDescription>
            A reusable text definition with <code>{"{{variable}}"}</code>{" "}
            placeholders. Nothing here is turned into audio — the platform
            synthesises text like this during a call.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p
            role="alert"
            className="mx-6 rounded bg-destructive/10 p-3 text-sm font-medium text-destructive"
          >
            {alert}
          </p>
        ) : null}

        <FormProvider {...form}>
          <form
            onSubmit={form.handleSubmit(onSubmit)}
            noValidate
            className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6"
          >
            <FieldGroup>
              <FieldSet>
                <FieldLegend>Ownership</FieldLegend>

                {createOptions.scopes.length > 1 ? (
                  <div className="space-y-2">
                    <Label htmlFor="tts-create-scope">Scope</Label>
                    <Select
                      value={effectiveScope}
                      onValueChange={(value) => {
                        setScope(value as TtsTemplateScope);
                        setTargetTenantId(NO_TENANT);
                      }}
                    >
                      <SelectTrigger id="tts-create-scope">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="TENANT">Tenant template</SelectItem>
                        <SelectItem value="GLOBAL">
                          Global template (shared with every organization)
                        </SelectItem>
                      </SelectContent>
                    </Select>
                    <FieldDescription>
                      {effectiveScope === "GLOBAL" ? (
                        <>
                          A global template is owned by the platform, belongs to
                          no single organization, and is created{" "}
                          <strong>already approved</strong> because it is part of
                          the shared catalog.
                        </>
                      ) : (
                        <>
                          A tenant template belongs to one organization, is
                          created <strong>pending approval</strong>, and only that
                          organization can change it.
                        </>
                      )}
                    </FieldDescription>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <Label>Scope</Label>
                    <div>
                      <TtsTemplateScopeBadge scope="TENANT" />
                    </div>
                    <FieldDescription>
                      Templates you create belong to your organization and are
                      created pending approval.
                    </FieldDescription>
                  </div>
                )}

                {needsTenant ? (
                  <div className="space-y-2">
                    <Label htmlFor="tts-create-tenant">
                      Organization
                    </Label>
                    <Select
                      value={targetTenantId}
                      onValueChange={setTargetTenantId}
                    >
                      <SelectTrigger id="tts-create-tenant">
                        <SelectValue placeholder="Choose an organization…" />
                      </SelectTrigger>
                      <SelectContent>
                        {activeTenants.map((tenant) => (
                          <SelectItem key={tenant.id} value={tenant.id}>
                            {tenant.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FieldDescription>
                      Only active organizations are listed. The list comes from
                      the organizations you are authorized to administer — this
                      is a choice of target, not a way to widen your own access.
                    </FieldDescription>
                    {tenantsQuery.isError ? (
                      <QueryErrorState
                        error={tenantsQuery.error}
                        entityLabel="organizations"
                        onRetry={() => void tenantsQuery.refetch()}
                      />
                    ) : null}
                  </div>
                ) : null}
              </FieldSet>

              <FieldSet>
                <FieldLegend>Template</FieldLegend>
                <TextField
                  label="Name"
                  placeholder="Welcome call opening"
                  registration={form.register("name")}
                  error={form.getFieldState("name").error?.message}
                />
                <TextareaField
                  label="Description"
                  placeholder="Optional notes about this template"
                  registration={form.register("description")}
                  error={form.getFieldState("description").error?.message}
                  rows={2}
                />
                <TextareaField
                  label="Template text"
                  registration={form.register("templateText")}
                  error={form.getFieldState("templateText").error?.message}
                  rows={5}
                  placeholder="Hello {{firstName}}, your balance is {{balance}}."
                  description="Use {{variableName}} placeholders. Every placeholder must name a variable you declare below, and no other curly braces are allowed."
                />
              </FieldSet>

              <TtsTemplateVariablesField disabled={pending} />
            </FieldGroup>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => handleOpenChange(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={pending}>
                {pending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Creating…
                  </>
                ) : (
                  "Create template"
                )}
              </Button>
            </DialogFooter>
          </form>
        </FormProvider>
      </DialogContent>
    </Dialog>
  );
}
