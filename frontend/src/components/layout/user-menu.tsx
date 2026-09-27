"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { LogOutIcon, ShieldCheckIcon, UserRoundIcon } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SidebarMenuButton } from "@/components/ui/sidebar";
import { ChangePasswordDialog } from "@/components/account/change-password-dialog";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import { useLogout, useLogoutAll } from "@/lib/session";

/** Derives initials from the verified email — never fabricated profile data. */
function initialsFor(email: string): string {
  const [localPart] = email.split("@");
  const cleaned = (localPart ?? "").replace(/[^a-z0-9]/gi, "");
  return cleaned.slice(0, 2).toUpperCase() || "?";
}

export function UserMenu({ user }: { user: AuthenticatedUserResponse }) {
  const router = useRouter();
  const logout = useLogout();
  const logoutAll = useLogoutAll();
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <SidebarMenuButton size="lg" aria-label="Account menu">
            <span
              aria-hidden="true"
              className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground"
            >
              {initialsFor(user.email)}
            </span>
            <span className="min-w-0 truncate text-left text-sm leading-tight">
              <span className="block truncate font-medium">{user.email}</span>
              <span className="block truncate text-muted-foreground">
                Account settings
              </span>
            </span>
          </SidebarMenuButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent side="top" align="start" className="w-60">
          <DropdownMenuLabel className="truncate">
            {user.email}
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem onClick={() => router.push("/account")}>
              <UserRoundIcon aria-hidden="true" />
              Account
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => setChangePasswordOpen(true)}>
              <ShieldCheckIcon aria-hidden="true" />
              Change password…
            </DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
          >
            <LogOutIcon aria-hidden="true" />
            Sign out
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => logoutAll.mutate()}
            disabled={logoutAll.isPending}
          >
            <LogOutIcon aria-hidden="true" />
            Sign out of all devices
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <ChangePasswordDialog
        open={changePasswordOpen}
        onOpenChange={setChangePasswordOpen}
      />
    </>
  );
}
