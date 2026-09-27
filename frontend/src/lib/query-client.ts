import { QueryClient } from "@tanstack/react-query";

/**
 * Application-wide QueryClient.
 *
 * All data fetching in this app is client-side ("use client" boundaries),
 * so a single shared instance is safe and lets non-React modules
 * (API interceptors, logout flows) clear cache state imperatively.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30_000,
    },
    mutations: {
      retry: false,
    },
  },
});
