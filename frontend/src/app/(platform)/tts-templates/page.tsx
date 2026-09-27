import type { Metadata } from "next";
import { TtsTemplatesView } from "@/components/tts-templates/tts-templates-view";
export const dynamic="force-dynamic";
export const metadata: Metadata = { title:"TTS Templates" };
export default function Page(){ return <TtsTemplatesView />; }
