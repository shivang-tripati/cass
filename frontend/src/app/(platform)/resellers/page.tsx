import type { Metadata } from "next";

import { ResellersView } from "@/components/resellers/resellers-view";

// List state lives in the URL and is user-scoped; SSR per request keeps
// useSearchParams-based state handling simple and correct.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Resellers",
};

export default function ResellersPage() {
  return <ResellersView />;
}
