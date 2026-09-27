"use client";

import { useState } from "react";

import { ChangePasswordDialog } from "@/components/account/change-password-dialog";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
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
import type { LifecycleStatus } from "@/lib/api/types";
import { useSession } from "@/lib/session";

function StatusBadge({ status }: { status: LifecycleStatus }) {
  return (
    <Badge variant={status === "ACTIVE" ? "outline" : "destructive"}>
      <span
        aria-hidden="true"
        className={
          status === "ACTIVE"
            ? "size-1.5 rounded-full bg-emerald-500"
            : "size-1.5 rounded-full bg-current"
        }
      />
      {status}
    </Badge>
  );
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-[8rem_1fr] items-start gap-2 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="break-all font-medium">{value}</dd>
    </div>
  );
}

/**
 * Account summary built exclusively from GET /api/v1/auth/me fields
 * (id, email, status). The backend exposes no further profile data yet.
 */
export function AccountView() {
  const session = useSession();
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);

  if (!session.data) {
    return null;
  }
  const user = session.data;

  return (
    <div className="mx-auto w-full max-w-4xl">
      <PageHeader
        title="Account"
        description="Your profile and security settings."
      />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Profile</CardTitle>
            <CardDescription>Identity details of your account.</CardDescription>
          </CardHeader>
          <CardContent>
            <dl className="space-y-3">
              <ProfileRow label="Email" value={user.email} />
              <Separator />
              <ProfileRow label="User ID" value={user.id} />
              <Separator />
              <div className="grid grid-cols-[8rem_1fr] items-center gap-2 text-sm">
                <dt className="text-muted-foreground">Status</dt>
                <dd>
                  <StatusBadge status={user.status} />
                </dd>
              </div>
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Security</CardTitle>
            <CardDescription>
              Manage credentials for your account.
            </CardDescription>
            <CardAction>
              <Button onClick={() => setChangePasswordOpen(true)}>
                Change password
              </Button>
            </CardAction>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              Changing your password signs you out of all devices for
              security.
            </p>
          </CardContent>
        </Card>
      </div>

      <ChangePasswordDialog
        open={changePasswordOpen}
        onOpenChange={setChangePasswordOpen}
      />
    </div>
  );
}
