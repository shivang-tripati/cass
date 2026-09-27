import { Badge } from "@/components/ui/badge";
import type { ContentMode } from "@/lib/api/contracts";

const CONTENT_MODE_META: Record<
  ContentMode,
  { label: string; className: string }
> = {
  AUDIO: {
    label: "Audio",
    className:
      "bg-blue-500/10 text-blue-700 dark:text-blue-400 border-blue-500/30",
  },
  TTS: {
    label: "TTS",
    className:
      "bg-green-500/10 text-green-700 dark:text-green-400 border-green-500/30",
  },
};

/**
 * Text + tinted badge for a backend ContentMode value.
 */
export function ContentModeBadge({
  contentMode,
}: {
  contentMode: ContentMode;
}) {
  const meta = CONTENT_MODE_META[contentMode] ?? {
    label: contentMode,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}