import type { Metadata } from "next";

import { ContactDetailView } from "@/components/contacts/contact-detail-view";

export const metadata: Metadata = {
  title: "Contact",
};

/**
 * F2 — this route did not exist. `ContactTable` linked here and every "View"
 * button on a contact resolved to the not-found page. It is backed by the real
 * endpoint `GET /api/v1/contact-groups/{contactGroupId}/contacts/{contactId}`.
 *
 * Note the shape: the contact is nested under its group in BOTH the route and
 * the API, because `ContactResponse` has no group id — the group is the parent
 * segment. Next.js 16 delivers route params as a Promise to server components.
 */
export default async function ContactPage({
  params,
}: {
  params: Promise<{ contactGroupId: string; contactId: string }>;
}) {
  const { contactGroupId, contactId } = await params;
  return (
    <ContactDetailView contactGroupId={contactGroupId} contactId={contactId} />
  );
}
