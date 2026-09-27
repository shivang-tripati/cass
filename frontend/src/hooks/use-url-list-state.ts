"use client";

import { useCallback, useMemo } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import type { LifecycleStatus } from "@/lib/api/types";

/**
 * URL-backed list state shared by admin table views (Sprint 2/3 pattern).
 * Owns parsing/validation of search params and URL patching; the owning
 * view maps fields onto its backend sort whitelist.
 *
 * Conventions (established in User Management):
 * - q: free-text search
 * - status: ACTIVE | SUSPENDED | (absent = all)
 * - page: 1-based in the URL, zero-based toward the API
 * - size: restricted to the configured options
 * - sort/dir: only when deviating from defaultSort
 */

export interface ListUrlState {
  q: string;
  status: LifecycleStatus | "";
  page: number; // zero-based
  size: number;
  sort: { field: string; direction: "asc" | "desc" };
}

interface UrlListConfig {
  /** Backend-whitelisted sort fields; anything else falls back to default. */
  sortableFields: readonly string[];
  /** Backend default sort; the URL omits sort/dir while this is active. */
  defaultSort: { field: string; direction: "asc" | "desc" };
  pageSizes: readonly number[];
  basePath: string;
}

export function useUrlListState(config: UrlListConfig) {
  const router = useRouter();
  const searchParams = useSearchParams();

  const state = useMemo<ListUrlState>(() => {
    const q = searchParams.get("q") ?? "";
    const statusParam = searchParams.get("status");
    const status: LifecycleStatus | "" =
      statusParam === "ACTIVE" || statusParam === "SUSPENDED"
        ? statusParam
        : "";
    const pageParam = Number.parseInt(searchParams.get("page") ?? "1", 10);
    const page =
      Number.isFinite(pageParam) && pageParam > 0 ? pageParam - 1 : 0;
    const sizeParam = Number.parseInt(searchParams.get("size") ?? "", 10);
    const size = config.pageSizes.includes(sizeParam)
      ? sizeParam
      : config.pageSizes[0];
    const sortFieldParam = searchParams.get("sort");
    const isSortable =
      sortFieldParam !== null &&
      config.sortableFields.includes(sortFieldParam);
    const sort = {
      field: isSortable ? sortFieldParam : config.defaultSort.field,
      direction:
        isSortable && searchParams.get("dir") === "asc"
          ? ("asc" as const)
          : config.defaultSort.direction,
    };
    return { q, status, page, size, sort };
  }, [searchParams, config]);

  /**
   * Merges a partial update into the URL. `resetPage` returns to the first
   * page (used whenever filters/search change the dataset).
   */
  const patch = useCallback(
    (partial: Partial<Omit<ListUrlState, "sort">> & { sort?: ListUrlState["sort"] }, resetPage: boolean) => {
      const merged: ListUrlState = { ...state, ...partial };
      if (resetPage) merged.page = 0;

      const next = new URLSearchParams();
      if (merged.q) next.set("q", merged.q);
      if (merged.status) next.set("status", merged.status);
      if (merged.page > 0) next.set("page", String(merged.page + 1));
      if (!config.pageSizes.includes(merged.size))
        next.set("size", String(merged.size));
      if (
        merged.sort.field !== config.defaultSort.field ||
        merged.sort.direction !== config.defaultSort.direction
      ) {
        next.set("sort", merged.sort.field);
        next.set("dir", merged.sort.direction);
      }
      router.replace(
        `${config.basePath}${[...next.keys()].length > 0 ? `?${next}` : ""}`,
        { scroll: false },
      );
    },
    [router, state, config],
  );

  return { state, patch };
}
