import { Badge } from "@/components/ui/badge";
import type { NumberType } from "@/lib/api/contracts";

const NUMBER_TYPE_META: Record<
  NumberType,
  { label: string; className: string }
> = {
  LANDLINE: {
    label: "Landline",
    className:
      "bg-slate-500/10 text-slate-700 dark:text-slate-400 border-slate-500/30",
  },
  MOBILE: {
    label: "Mobile",
    className:
      "bg-indigo-500/10 text-indigo-700 dark:text-indigo-400 border-indigo-500/30",
  },
  PROMOTIONAL_140: {
    label: "Promotional (140)",
    className:
      "bg-amber-500/10 text-amber-700 dark:text-amber-400 border-amber-500/30",
  },
};

/**
 * Text + tinted badge for a backend NumberType value.
 */
export function NumberTypeBadge({
  numberType,
}: {
  numberType: NumberType;
}) {
  const meta = NUMBER_TYPE_META[numberType] ?? {
    label: numberType,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}