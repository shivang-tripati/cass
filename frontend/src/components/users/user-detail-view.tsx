"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, PencilIcon, TriangleAlertIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { EditUserDialog } from "@/components/users/edit-user-dialog";
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
import { usersKeys, getUser } from "@/lib/api/users";
import { formatDateTime } from "@/lib/format";

const HOME_LABELS: Record<string, string> = {
  TENANT: "Tenant",
  RESELLER: "Reseller",
};

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
        <Skeleton className="h-5 w-24" />
        <Skeleton className="h-4 w-48" />
      </CardHeader>
      <CardContent className="space-y-4">
        {[80, 128, 96, 112].map((width) => (
          <Skeleton key={width} className="h-4" style={{ width }} />
        ))}
      </CardContent>
    </Card>
  );
}

export function UserDetailView({ userId }: { userId: string }) {
  const detailQuery = useQuery({
    queryKey: usersKeys.detail(userId),
    queryFn: () => getUser(userId),
    retry: false,
  });
  const [editOpen, setEditOpen] = useState(false);

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
            ? "User not found"
            : apiError.status === 403
              ? "You don't have permission to view this user."
              : apiError.message}
        </AlertTitle>
        <AlertDescription>
          {notFound || apiError.status === 403
            ? "The account may have been removed or is outside your organization scope."
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
          <Link href="/users">Back to users</Link>
        </Button>
      </Alert>
    );
  }

  const user = detailQuery.data;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-4">
      <Button variant="ghost" size="sm" asChild className="-ml-2">
        <Link href="/users">
          <ArrowLeftIcon aria-hidden="true" />
          Users
        </Link>
      </Button>

      <PageHeader
        title={user.displayName ?? user.email}
        description={
          user.displayName ? `User account · ${user.email}` : "User account"
        }
      />

      <Card>
        <CardHeader>
          <CardTitle>Profile</CardTitle>
          <CardDescription>
            Identity and organizational binding of this account.
          </CardDescription>
          <CardAction>
            <Button onClick={() => setEditOpen(true)}>
              <PencilIcon aria-hidden="true" />
              Edit user
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent>
          <dl className="space-y-4">
            <DetailRow label="Email">{user.email}</DetailRow>
            <Separator />
            <DetailRow label="Display name">
              {user.displayName ?? (
                <span className="text-muted-foreground">Not set</span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Status">
              <LifecycleStatusBadge status={user.status} />
            </DetailRow>
            <Separator />
            <DetailRow label="Organization">
              {user.homeType ? (
                <span className="flex flex-col gap-0.5">
                  <span>{HOME_LABELS[user.homeType] ?? user.homeType}</span>
                  <code className="text-xs text-muted-foreground">
                    {user.organizationId}
                  </code>
                </span>
              ) : (
                <span className="text-muted-foreground">Platform</span>
              )}
            </DetailRow>
            <Separator />
            <DetailRow label="Created">
              <time dateTime={user.createdAt}>
                {formatDateTime(user.createdAt)}
              </time>
            </DetailRow>
          </dl>
        </CardContent>
      </Card>

      {editOpen ? (
        <EditUserDialog user={user} onOpenChange={setEditOpen} />
      ) : null}
    </div>
  );
}

