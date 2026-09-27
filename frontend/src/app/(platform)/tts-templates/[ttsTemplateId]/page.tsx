import { TtsTemplateDetailView } from "@/components/tts-templates/tts-template-detail-view";
export const dynamic="force-dynamic";
export default async function Page({ params }: { params: Promise<{ ttsTemplateId: string }> }){ const { ttsTemplateId } = await params; return <TtsTemplateDetailView id={ttsTemplateId} />; }
