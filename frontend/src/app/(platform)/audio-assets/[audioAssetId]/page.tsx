import { AudioAssetDetailView } from "@/components/audio-assets/audio-asset-detail-view";
export const dynamic = "force-dynamic";
export default async function Page({ params }: { params: Promise<{ audioAssetId: string }> }){
  const { audioAssetId } = await params;
  return <AudioAssetDetailView id={audioAssetId} />;
}
