import type { Metadata } from "next";

import { ResellerDetailView } from "@/components/resellers/reseller-detail-view";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Reseller",
};

/** Next.js 16 delivers route params as a Promise to server components. */
export default async function ResellerDetailPage({
  params,
}: {
  params: Promise<{ resellerId: string }>;
}) {
  const { resellerId } = await params;
  return <ResellerDetailView resellerId={resellerId} />;
}
