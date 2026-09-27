import type { Metadata } from "next";

import { ContactGroupDetailView } from "@/components/contact-groups/contact-group-detail-view";

export const dynamic = "force-dynamic";

interface ContactGroupPageProps {
  params: Promise<{ contactGroupId: string }>;
}

export const metadata: Metadata = {
  title: "Contact Group",
};

export default async function ContactGroupPage({ params }: ContactGroupPageProps) {
  const { contactGroupId } = await params;
  return <ContactGroupDetailView groupId={contactGroupId} />;
}