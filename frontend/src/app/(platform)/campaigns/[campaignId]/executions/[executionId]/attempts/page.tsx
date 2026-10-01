import { ExecutionAttemptsView } from "@/components/campaigns/execution-attempts-view";

export const dynamic = "force-dynamic";

export default async function Page({
  params,
}: {
  params: Promise<{ campaignId: string; executionId: string }>;
}) {
  const { campaignId, executionId } = await params;
  return <ExecutionAttemptsView campaignId={campaignId} executionId={executionId} />;
}
