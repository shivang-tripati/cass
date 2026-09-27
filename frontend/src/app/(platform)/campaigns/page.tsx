import type { Metadata } from "next";

import { CampaignsView } from "@/components/campaigns/campaigns-view";

// List state lives in the URL and is user-scoped; SSR per request keeps
// useSearchParams-based state handling simple and correct.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Campaigns",
};

export default function CampaignsPage() {
  return <CampaignsView />;
}