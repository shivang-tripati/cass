import { Spinner } from "@/components/ui/spinner";

/**
 * F1 — Suspense fallback for the route group's own server-segment transitions.
 *
 * Distinct from the per-view `TableSkeleton`: that one preserves table
 * structure while a query is in flight, while this covers navigation between
 * routes, where no list is mounted yet. The spinner carries `role="status"` and
 * a screen-reader label so the wait is announced rather than silent.
 */
export default function AuthGroupLoading() {
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex min-h-[50vh] items-center justify-center p-6"
    >
      <Spinner className="size-6" />
      <span className="sr-only">Loading…</span>
    </div>
  );
}
