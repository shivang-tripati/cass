import type { Metadata } from "next";

import { UserDetailView } from "@/components/users/user-detail-view";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "User",
};

/** Next.js 16 delivers route params as a Promise to server components. */
export default async function UserDetailPage({
  params,
}: {
  params: Promise<{ userId: string }>;
}) {
  const { userId } = await params;
  return <UserDetailView userId={userId} />;
}
