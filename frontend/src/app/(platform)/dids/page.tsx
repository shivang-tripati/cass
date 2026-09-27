import type { Metadata } from "next";

import { DidsView } from "@/components/dids/dids-view";

// List state lives in the URL and is user-scoped; SSR per request keeps
// useSearchParams-based state handling simple and correct.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "DIDs",
};

export default function DidsPage() {
  return <DidsView />;
}