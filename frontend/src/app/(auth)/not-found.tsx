import Link from "next/link";
import { SearchXIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * F1 — 404 for unmatched routes inside the authenticated groups.
 *
 * F0 recorded that no `not-found.tsx` existed, so an unknown URL produced the
 * default Next.js page with no navigation back into the app.
 *
 * Wording note: this is deliberately not "you don't have access". A 404 from
 * these APIs and an unmatched URL are different things, and the backend also
 * reports a resource outside the caller's boundary as a 404 — deliberately
 * indistinguishable from a missing one. This page only ever means "no route
 * matched".
 */
export default function NotFound() {
  return (
    <div className="mx-auto flex w-full max-w-2xl p-6">
      <Card className="w-full">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <SearchXIcon className="size-5 text-muted-foreground" aria-hidden="true" />
            Page not found
          </CardTitle>
          <CardDescription>
            The address you followed does not match any page in this
            application.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild>
            <Link href="/">Go to your account</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
