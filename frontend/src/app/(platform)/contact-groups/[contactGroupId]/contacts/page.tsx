import type { Metadata } from "next";

import { ContactsView } from "@/components/contacts/contacts-view";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Contacts",
};

export default async function ContactsPage({ params }: { params: Promise<{ contactGroupId: string }> }) {
  const { contactGroupId } = await params;
  return <ContactsView contactGroupId={contactGroupId} />;
}