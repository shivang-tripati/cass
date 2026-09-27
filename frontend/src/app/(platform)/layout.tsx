"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { RadioTowerIcon, UserIcon } from "lucide-react";

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
import { useSession } from "@/lib/session";

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
 * Authenticated application shell. The session gate is a UX affordance —
 * every API call remains protected by backend authorization.
 */
export default function PlatformLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const router = useRouter();
  const session = useSession();

  useEffect(() => {
    if (session.isError) {
      router.replace("/sign-in");
    }
  }, [session.isError, router]);

  if (session.isPending || session.isError) {
    return <PlatformSkeleton />;
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
