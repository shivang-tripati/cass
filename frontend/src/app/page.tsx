"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

import { Spinner } from "@/components/ui/spinner";
import { useSession } from "@/lib/session";

/**
 * Session-aware entry point. There is intentionally no public homepage:
 * authenticated users go to the platform, everyone else to sign-in.
 */
export default function Home() {
  const router = useRouter();
  const session = useSession();

  useEffect(() => {
    if (session.isSuccess) {
      router.replace("/account");
    } else if (session.isError) {
      router.replace("/sign-in");
    }
  }, [session.isSuccess, session.isError, router]);

  return (
    <div
      className="flex flex-1 items-center justify-center"
      aria-busy={session.isPending}
      aria-live="polite"
    >
      <span className="sr-only">Loading…</span>
      <Spinner className="size-6" />
    </div>
  );
}
