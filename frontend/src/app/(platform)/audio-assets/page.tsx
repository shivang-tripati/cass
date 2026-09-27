import type { Metadata } from "next";
import { AudioAssetsView } from "@/components/audio-assets/audio-assets-view";
export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Audio Assets" };
export default function Page(){ return <AudioAssetsView />; }
