import type { Metadata } from "next";

import { TenantsView } from "@/components/tenants/tenants-view";

// List state lives in the URL and is user-scoped; SSR per request keeps
// useSearchParams-based state handling simple and correct.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Tenants",
};

export default function TenantsPage() {
  return <TenantsView />;
}
