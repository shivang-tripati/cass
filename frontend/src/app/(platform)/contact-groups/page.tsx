import type { Metadata } from "next";

import { ContactGroupsView } from "@/components/contact-groups/contact-groups-view";

// List state lives in the URL and is user-scoped; SSR per request keeps
// useSearchParams-based state handling simple and correct.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Contact Groups",
};

export default function ContactGroupsPage() {
  return <ContactGroupsView />;
}