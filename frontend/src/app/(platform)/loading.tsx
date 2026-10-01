import { Spinner } from "@/components/ui/spinner";

/**
 * F1 — Suspense fallback for the (platform) route group's server-segment
 * transitions.
 *
 * Distinct from the per-view `TableSkeleton`: that one preserves table structure
 * while a query is in flight, while this covers navigation between routes, where
 * no list is mounted yet. `role="status"` plus a screen-reader label means the
 * wait is announced rather than silent.
 */
export default function PlatformGroupLoading() {
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
