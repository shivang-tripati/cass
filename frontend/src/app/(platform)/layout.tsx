"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { RadioTowerIcon, RefreshCwIcon, UserIcon } from "lucide-react";

import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
} from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/layout/app-sidebar";
import { SiteHeader } from "@/components/layout/site-header";
import { isRetryable, isUnauthorized } from "@/lib/api/error";
import { endLocalSession, useSession } from "@/lib/session";

/** F1: the state `/me` lands in when it fails for a reason other than an ended
 * session. Without this, a 5xx or an offline moment looked identical to a
 * logout and threw the user out of an otherwise valid session. */
function SessionUnavailable({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="mx-auto flex w-full max-w-2xl p-6">
      <Card className="w-full">
        <CardHeader>
          <CardTitle>Cannot load your session</CardTitle>
          <CardDescription>
            You are still signed in. The server could not confirm your account
            just now.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Alert variant="destructive">
            <AlertTitle>Request failed</AlertTitle>
            <AlertDescription>
              This is usually temporary. Try again, and if it persists contact
              an administrator.
            </AlertDescription>
            <AlertAction>
              <Button variant="outline" size="sm" onClick={onRetry}>
                <RefreshCwIcon aria-hidden="true" />
                Retry
              </Button>
            </AlertAction>
          </Alert>
        </CardContent>
      </Card>
    </div>
  );
}

/** Static loading structure mirroring the shell to avoid layout flash. */
function PlatformSkeleton() {
  return (
    <SidebarProvider>
      <Sidebar collapsible="none">
        <SidebarHeader>
          <div className="flex items-center gap-2 px-2 py-1.5">
            <span
              aria-hidden="true"
              className="flex size-7 shrink-0 items-center justify-center rounded-md bg-muted"
            >
              <RadioTowerIcon className="size-4 text-muted-foreground" />
            </span>
            <Skeleton className="h-4 w-24" />
          </div>
        </SidebarHeader>
        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupLabel>Platform</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton disabled>
                    <UserIcon aria-hidden="true" />
                    <Skeleton className="h-4 w-16" />
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>
        <SidebarFooter>
          <div className="flex items-center gap-2 px-2 py-1.5">
            <Skeleton className="size-7 rounded-full" />
            <Skeleton className="h-4 w-28" />
          </div>
        </SidebarFooter>
      </Sidebar>
      <SidebarInset>
        <SiteHeader />
        <main className="flex-1 space-y-6 p-4 md:p-6">
          <div className="space-y-2">
            <Skeleton className="h-7 w-40" />
            <Skeleton className="h-4 w-64" />
          </div>
          <Skeleton className="h-48 w-full max-w-2xl rounded-xl" />
        </main>
      </SidebarInset>
    </SidebarProvider>
  );
}

/**
 * Authenticated application shell.
 *
 * F1 — why there is still no `middleware.ts`.
 * A server-side guard cannot validate this session. The access token lives only
 * in client memory, and the refresh cookie is HttpOnly AND path-scoped to
 * `/api/v1/auth` (AuthCookieWriter L20-21), so it is not even attached to a
 * page request. Middleware would have nothing to read, and inventing a second,
 * non-HttpOnly "logged in" cookie purely to gate navigation would create a new
 * credential to leak. So the gate stays client-side, and the security boundary
 * remains the backend, which authorizes every request independently.
 *
 * What F1 did fix here:
 *  - protected content is never rendered while the session is unresolved: the
 *    skeleton is returned for `isPending` and `isError` before `children` is
 *    reached, so there is no flash of the authenticated tree to an anonymous
 *    visitor. This behaviour already existed; F1 makes it explicit and covered
 *    by a comment rather than incidental.
 *  - the deep link is now preserved. F0 redirected to a bare `/sign-in` from
 *    here while `client.ts` preserved `?next=`, so a session expiry deep inside
 *    the app lost the user's place.
 *  - an unexpected non-auth error on /me (5xx, offline) no longer bounces to
 *    sign-in; it renders a retry state, because the user is still signed in.
 */
export default function PlatformLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const router = useRouter();
  const pathname = usePathname();
  const session = useSession();

  // A 401 means the session is gone: end it locally and go to sign-in, keeping
  // the deep link. Any other failure is not an auth problem and must not
  // masquerade as one.
  const isUnauthenticated = isUnauthorized(session.error);
  const isRetryableFailure =
    session.isError && !isUnauthenticated && isRetryable(session.error);

  useEffect(() => {
    if (isUnauthenticated) {
      endLocalSession();
      const next = pathname === "/" ? "" : `?next=${encodeURIComponent(pathname)}`;
      router.replace(`/sign-in${next}`);
    }
  }, [isUnauthenticated, pathname, router]);

  if (session.isPending) {
    return <PlatformSkeleton />;
  }

  if (isUnauthenticated) {
    return <PlatformSkeleton />;
  }

  if (isRetryableFailure || !session.data) {
    return <SessionUnavailable onRetry={() => void session.refetch()} />;
  }

  const user = session.data;

  return (
    <SidebarProvider>
      {/* Accessible skip target for keyboard users. */}
      <a
        href="#platform-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-md focus:bg-background focus:px-3 focus:py-2 focus:text-sm focus:shadow"
      >
        Skip to content
      </a>
      <AppSidebar user={user} />
      <SidebarInset>
        <SiteHeader />
        <main
          id="platform-content"
          tabIndex={-1}
          className="flex-1 p-4 md:p-6"
        >
          {children}
        </main>
      </SidebarInset>
    </SidebarProvider>
  );
}
