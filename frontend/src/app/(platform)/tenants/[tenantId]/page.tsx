import type { Metadata } from "next";

import { TenantDetailView } from "@/components/tenants/tenant-detail-view";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Tenant",
};

/** Next.js 16 delivers route params as a Promise to server components. */
export default async function TenantDetailPage({
  params,
}: {
  params: Promise<{ tenantId: string }>;
}) {
  const { tenantId } = await params;
  return <TenantDetailView tenantId={tenantId} />;
}
