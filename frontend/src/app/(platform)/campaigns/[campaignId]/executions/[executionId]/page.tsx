import { ExecutionDetailView } from "@/components/campaigns/execution-detail-view";
export const dynamic = "force-dynamic";
export default async function Page({ params }: { params: Promise<{ campaignId: string; executionId: string }> }){
  const { campaignId, executionId } = await params;
  return <ExecutionDetailView campaignId={campaignId} executionId={executionId} />;
}
