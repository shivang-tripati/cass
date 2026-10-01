"use client";

import { useEffect } from "react";
import { AlertTriangleIcon, RefreshCwIcon } from "lucide-react";

import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * F1 — error boundary for the (auth) route group (sign-in and the two signup
 * pages).
 *
 * F0 recorded that NO error boundary existed anywhere in the app, so any
 * render-time throw fell through to Next.js's own handling: a development
 * overlay, or in production an opaque server-rendered error page with no way
 * back into the app.
 *
 * Deliberately does NOT print the raw error. `reset()` re-renders the segment.
 * The boundary sits on the group, not on the individual pages, because all
 * three share the same shell and a failure in one should not take the others
 * down with it.
 *
 * The single logged field is `digest`, Next.js's build-scoped identifier, which
 * identifies no resource. No tokens, cookies, Authorization headers, request
 * bodies or credentials are logged — see the F1 doc §10.
 */
export default function AuthGroupError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("[auth] render error", { digest: error.digest });
  }, [error]);

  return (
    <div className="mx-auto flex w-full max-w-2xl p-6">
      <Card className="w-full">
        <CardHeader>
          <CardTitle>Something went wrong</CardTitle>
        </CardHeader>
        <CardContent>
          <Alert variant="destructive">
            <AlertTriangleIcon aria-hidden="true" />
            <AlertTitle>This page could not be displayed</AlertTitle>
            <AlertDescription>
              {error.digest ? `Reference: ${error.digest}. ` : ""}
              You can try again — if it keeps happening, contact an
              administrator.
            </AlertDescription>
            <AlertAction>
              <Button variant="outline" size="sm" onClick={reset}>
                <RefreshCwIcon aria-hidden="true" />
                Try again
              </Button>
            </AlertAction>
          </Alert>
        </CardContent>
      </Card>
    </div>
  );
}
