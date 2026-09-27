import { z } from "zod";
import type { CreateTtsTemplatePayload, UpdateTtsTemplatePayload, TtsTemplateVariable } from "@/lib/api/contracts";

const varName = /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/;
const allowedTypes = ["STRING", "NUMBER", "BOOLEAN", "DATE"] as const;

const variableSchema = z.object({
  name: z.string().trim().min(1, "Variable name required").max(64).regex(varName, "Letters, digits, underscore, starting with letter"),
  type: z.enum(allowedTypes).optional().nullable().or(z.literal("").transform(() => undefined)),
  required: z.boolean().optional().nullable(),
});

const base = {
  name: z.string().trim().min(1, "Name required").max(150),
  description: z.string().trim().max(5000).optional().nullable().or(z.literal("").transform(() => undefined)),
  templateText: z.string().trim().min(1, "Template text required").max(5000),
  variables: z.array(variableSchema).max(50).default([]),
};

export const createTtsTemplateSchema = z.object(base).superRefine((v, ctx) => {
  const names = v.variables.map(x => x.name.trim());
  if (new Set(names).size !== names.length) ctx.addIssue({ code: "custom", path: ["variables"], message: "Duplicate variable name" });
  // ponytail: placeholder agreement validated server-side; client only checks stray braces lightly
  const stray = /(?<!\{)\{(?!\{)|(?<!\})\}(?!\})/;
  if (stray.test(v.templateText)) ctx.addIssue({ code: "custom", path: ["templateText"], message: "Stray braces — only {{name}} allowed" });
});

export type CreateTtsTemplateValues = z.infer<typeof createTtsTemplateSchema>;
export function toCreatePayload(v: CreateTtsTemplateValues): CreateTtsTemplatePayload {
  return { name: v.name, description: v.description ?? undefined, templateText: v.templateText, variables: v.variables as TtsTemplateVariable[] };
}

export const updateTtsTemplateSchema = z.object(base);
export type UpdateTtsTemplateValues = z.infer<typeof updateTtsTemplateSchema>;
export function toUpdatePayload(v: UpdateTtsTemplateValues): UpdateTtsTemplatePayload {
  return { name: v.name, description: v.description ?? undefined, templateText: v.templateText, variables: v.variables as TtsTemplateVariable[] };
}
