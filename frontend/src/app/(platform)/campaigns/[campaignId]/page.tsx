import { CampaignDetailView } from "@/components/campaigns/campaign-detail-view";
export const dynamic = "force-dynamic";
export default async function Page({ params }: { params: Promise<{ campaignId: string }> }){
  const { campaignId } = await params;
  return <CampaignDetailView campaignId={campaignId} />;
}
