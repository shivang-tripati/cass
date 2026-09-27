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
import { getReseller, resellersKeys } from "@/lib/api/resellers";
import { formatDateTime } from "@/lib/format";
import { DeleteResellerDialog } from "@/components/resellers/delete-reseller-dialog";
import { EditResellerDialog } from "@/components/resellers/edit-reseller-dialog";

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
        <Skeleton className="h-5 w-36" />
        <Skeleton className="h-4 w-48" />
      </CardHeader>
      <CardContent className="space-y-4">
        {[96, 128, 112, 144, 128, 112].map((width) => (
          <Skeleton key={width} className="h-4" style={{ width }} />
        ))}
      </CardContent>
    </Card>
  );
}

export function ResellerDetailView({ resellerId }: { resellerId: string }) {
  const detailQuery = useQuery({
    queryKey: resellersKeys.detail(resellerId),
    queryFn: () => getReseller(resellerId),
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
            ? "Reseller not found"
            : apiError.status === 403
              ? "You don't have permission to view this reseller."
              : apiError.message}
        </AlertTitle>
        <AlertDescription>
          {notFound || apiError.status === 403
            ? "The reseller may have been deactivated or is outside your organization scope."
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
          <Link href="/resellers">Back to resellers</Link>
        </Button>
      </Alert>
    );
  }

  const reseller = detailQuery.data;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-4">
      <Button variant="ghost" size="sm" asChild className="-ml-2">
        <Link href="/resellers">
          <ArrowLeftIcon aria-hidden="true" />
          Resellers
        </Link>
      </Button>

      <PageHeader
        title={reseller.displayName ?? reseller.name}
        description={`Reseller · ${reseller.slug}`}
      />

      <Card>
        <CardHeader>
          <CardTitle>Overview</CardTitle>
          <CardDescription>
            Identity and branding of this reseller organization.
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
            <DetailRow label="Name">{reseller.name}</DetailRow>
            <Separator />
            <DetailRow label="Display name">
              {reseller.displayName ?? (
                <span className="text-muted-foreground">Not set</span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Slug">
              <code className="text-xs">{reseller.slug}</code>
            </DetailRow>
            <Separator />
            <DetailRow label="Status">
              <LifecycleStatusBadge status={reseller.status} />
            </DetailRow>
            <Separator />
            <DetailRow label="Support email">
              {reseller.supportEmail ?? (
                <span className="text-muted-foreground">Not set</span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Logo URL">
              {reseller.logoUrl ?? (
                <span className="text-muted-foreground">Not set</span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Primary color">
              {reseller.primaryColor ?? (
                <span className="text-muted-foreground">Not set</span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Created">
              <time dateTime={reseller.createdAt}>
                {formatDateTime(reseller.createdAt)}
              </time>
            </DetailRow>
            <Separator />
            <DetailRow label="Updated">
              <time dateTime={reseller.updatedAt}>
                {formatDateTime(reseller.updatedAt)}
              </time>
            </DetailRow>
          </dl>
        </CardContent>
      </Card>

      {editOpen ? (
        <EditResellerDialog
          key={reseller.id}
          reseller={reseller}
          onOpenChange={setEditOpen}
        />
      ) : null}
      {deleteOpen ? (
        <DeleteResellerDialog
          reseller={reseller}
          onOpenChange={setDeleteOpen}
        />
      ) : null}
    </div>
  );
}
