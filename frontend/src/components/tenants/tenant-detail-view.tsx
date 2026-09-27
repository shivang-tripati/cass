"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeftIcon,
  PencilIcon,
  Trash2Icon,
  TriangleAlertIcon,
} from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import {
  LifecycleStatusBadge,
} from "@/components/common/lifecycle-status-badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { toApiError } from "@/lib/api/error";
import { getTenant, tenantsKeys } from "@/lib/api/tenants";
import { formatDateTime } from "@/lib/format";
import { DeleteTenantDialog } from "@/components/tenants/delete-tenant-dialog";
import { EditTenantDialog } from "@/components/tenants/edit-tenant-dialog";

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem_1fr] items-start gap-2 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-all font-medium">{children}</dd>
    </div>
  );
}

function DetailSkeleton() {
  return (
    <Card aria-busy="true">
      <CardHeader>
        <Skeleton className="h-5 w-32" />
        <Skeleton className="h-4 w-48" />
      </CardHeader>
      <CardContent className="space-y-4">
        {[96, 128, 112, 144, 128].map((width) => (
          <Skeleton key={width} className="h-4" style={{ width }} />
        ))}
      </CardContent>
    </Card>
  );
}

export function TenantDetailView({ tenantId }: { tenantId: string }) {
  const detailQuery = useQuery({
    queryKey: tenantsKeys.detail(tenantId),
    queryFn: () => getTenant(tenantId),
    retry: false,
  });
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  if (detailQuery.isPending) {
    return <DetailSkeleton />;
  }

  if (detailQuery.isError) {
    const apiError = toApiError(detailQuery.error);
    const notFound = apiError.status === 404;
    return (
      <Alert variant="destructive" className="max-w-xl">
        <TriangleAlertIcon aria-hidden="true" />
        <AlertTitle>
          {notFound
            ? "Tenant not found"
            : apiError.status === 403
              ? "You don't have permission to view this tenant."
              : apiError.message}
        </AlertTitle>
        <AlertDescription>
          {notFound || apiError.status === 403
            ? "The tenant may have been deactivated or is outside your organization scope."
            : apiError.requestId
              ? `Request ID: ${apiError.requestId}`
              : null}
        </AlertDescription>
        {!notFound && apiError.status !== 403 ? (
          <Button size="sm" onClick={() => void detailQuery.refetch()}>
            Retry
          </Button>
        ) : null}
        <Button variant="outline" size="sm" asChild>
          <Link href="/tenants">Back to tenants</Link>
        </Button>
      </Alert>
    );
  }

  const tenant = detailQuery.data;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-4">
      <Button variant="ghost" size="sm" asChild className="-ml-2">
        <Link href="/tenants">
          <ArrowLeftIcon aria-hidden="true" />
          Tenants
        </Link>
      </Button>

      <PageHeader title={tenant.name} description={`Tenant · ${tenant.slug}`} />

      <Card>
        <CardHeader>
          <CardTitle>Overview</CardTitle>
          <CardDescription>
            Identity and organization binding of this tenant.
          </CardDescription>
          <CardAction>
            <div className="flex items-center gap-2">
              <Button variant="outline" onClick={() => setEditOpen(true)}>
                <PencilIcon aria-hidden="true" />
                Edit
              </Button>
              <Button variant="destructive" onClick={() => setDeleteOpen(true)}>
                <Trash2Icon aria-hidden="true" />
                Deactivate
              </Button>
            </div>
          </CardAction>
        </CardHeader>
        <CardContent>
          <dl className="space-y-4">
            <DetailRow label="Name">{tenant.name}</DetailRow>
            <Separator />
            <DetailRow label="Slug">
              <code className="text-xs">{tenant.slug}</code>
            </DetailRow>
            <Separator />
            <DetailRow label="Status">
              <LifecycleStatusBadge status={tenant.status} />
            </DetailRow>
            <Separator />
            <DetailRow label="Reseller">
              {tenant.resellerId ? (
                <span className="flex flex-col gap-0.5">
                  <span>Reseller-managed</span>
                  <code className="text-xs text-muted-foreground">
                    {tenant.resellerId}
                  </code>
                </span>
              ) : (
                <span className="text-muted-foreground">
                  Direct tenant (no reseller)
                </span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Created">
              <time dateTime={tenant.createdAt}>
                {formatDateTime(tenant.createdAt)}
              </time>
            </DetailRow>
            <Separator />
            <DetailRow label="Updated">
              <time dateTime={tenant.updatedAt}>
                {formatDateTime(tenant.updatedAt)}
              </time>
            </DetailRow>
          </dl>
        </CardContent>
      </Card>

      {editOpen ? (
        <EditTenantDialog
          key={tenant.id}
          tenant={tenant}
          onOpenChange={setEditOpen}
        />
      ) : null}
      {deleteOpen ? (
        <DeleteTenantDialog tenant={tenant} onOpenChange={setDeleteOpen} />
      ) : null}
    </div>
  );
}
