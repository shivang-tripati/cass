"use client";

import { useCallback, useState } from "react";
import { useFormContext } from "react-hook-form";
import { PlusIcon, Trash2Icon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { FieldDescription, FieldSet, FieldLegend } from "@/components/ui/field";
import { TextField } from "@/components/forms/text-field";
import { CONFIGURABLE_RETRY_CATEGORIES } from "@/lib/api/contracts";

/**
 * The retry policy editor: a flat default allowance plus per-category overrides.
 *
 * ## Two layers, and they behave differently
 *
 * VERIFIED `RetryPolicyConfig`: `maxAttempts` + `intervalSeconds` are a single
 * default applied to every retryable category that has no rule of its own, and
 * `rules` overrides it per category. The backend's own description says to
 * prefer the per-category MM:SS rules for new configurations.
 *
 * Two constraints a form must not let a user violate:
 *
 *  - `maxAttempts` counts retries BEYOND the initial attempt, so the maximum
 *    total attempts is `1 + maxAttempts`. `0` means no retries.
 *  - An enabled rule with `maxRetries > 0` REQUIRES a delay, formatted `MM:SS`
 *    with minutes 00-99 and seconds 00-59 (`RetryDelay.MM_SS`). A delay on a
 *    rule that can never fire is refused, which is why the field disappears
 *    rather than being disabled when a rule is off.
 *
 * ## Permanent failures are never retryable
 *
 * VERIFIED `RetryRuleCategory` has six constants, but `SWITCHED_OFF` and
 * `NOT_REACHABLE` receive no provider outcome today: such causes arrive as
 * `HANGUP` and are governed by the HANGUP rule. So this offers only the four
 * reachable categories — `CONFIGURABLE_RETRY_CATEGORIES` — and a rule can only
 * RESTRICT retries, never make a permanent failure retryable. The description
 * says so, because the opposite reading ("I can retry this however I like") is
 * exactly the misconfiguration the backend refuses.
 *
 * ## `intervalSeconds` is bounded at 5999, not 604800
 *
 * The F1 form carried `max="604800"` on this input. VERIFIED
 * `RetryPolicyConfig.intervalSeconds` is `@Min(1) @Max(5999)`, so the form
 * offered a value the server rejects with 400 and no actionable message. The
 * Zod schema was already correct; only the input's own `max` was not.
 *
 * ## Why the rule rows are local state
 *
 * The rule list has to grow, shrink and be reordered, and the delay column has
 * to appear and disappear per row. `useFieldArray` cannot be typed here: this
 * form is consumed through an untyped `useFormContext`, so its `TFieldValues`
 * is `FieldValues` and the `FieldArrayPath` constraint resolves to `never` —
 * the row shape would degrade to `Record<"id", string>` and lose every field.
 *
 * So the rows live in local state and the whole array is published back with
 * `setValue` on every change. The Zod schema remains the single authority on
 * the final shape, and nothing here asserts a form type it does not have.
 */

const CATEGORY_LABEL = {
  NO_ANSWER: "No answer",
  BUSY: "Busy",
  HANGUP: "Hang up",
  FAILED: "Failed",
} as const;

type ConfigurableCategory = keyof typeof CATEGORY_LABEL;

const CATEGORY_OPTIONS = CONFIGURABLE_RETRY_CATEGORIES.map((category) => ({
  value: category,
  label: CATEGORY_LABEL[category as ConfigurableCategory],
}));

export const RETRY_INTERVAL_SECONDS_MAX = 5999;

interface RetryRuleRow {
  category: string;
  enabled: boolean;
  maxRetries: number;
  retryDelay: string;
}

export function CampaignRetryPolicyField({ name }: { name: string }) {
  const {
    register,
    setValue,
    getValues,
    formState: { errors },
  } = useFormContext();

  const [rules, setRules] = useState<RetryRuleRow[]>(() => toRows(getValues(name)));

  // No re-seeding effect: the edit dialog keys this subtree on the campaign id,
  // so pointing it at a different campaign remounts the component and the lazy
  // `useState` initialiser re-reads the form. An effect here would set state on
  // every mount for no behavioural gain, and would be the kind of effect the
  // React Compiler lint rule is right to flag.

  const update = useCallback(
    (next: RetryRuleRow[]) => {
      setRules(next);
      // An empty array is written as `[]`, which the payload mapper sends as
      // "no rules" — the same thing the server stores when the block is absent.
      setValue(name, next, { shouldValidate: false, shouldDirty: true });
    },
    [name, setValue],
  );

  const addRule = () => {
    const used = new Set(rules.map((rule) => rule.category));
    // VERIFIED the backend rejects duplicate categories
    // (`RetryPolicyValidator.validateView`), and the picker only offers unused
    // ones, so this cannot produce a duplicate.
    const next = CATEGORY_OPTIONS.find((option) => !used.has(option.value));
    if (!next) return;
    update([
      ...rules,
      { category: next.value, enabled: true, maxRetries: 0, retryDelay: "" },
    ]);
  };

  return (
    <FieldSet>
      <FieldLegend>Retry policy</FieldLegend>
      <FieldDescription>
        How many times a failed call is retried, and how long to wait. Retries
        count beyond the first attempt, so 2 retries means at most 3 attempts in
        total. Permanent failures are never retried regardless of this setting.
      </FieldDescription>

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Default retries"
          type="number"
          inputMode="numeric"
          min={0}
          max={10}
          description="0 means no retries. Applies to any failure category without its own rule."
          registration={register(`${name}.maxAttempts`, { valueAsNumber: true })}
          error={fieldMessage(errors, `${name}.maxAttempts`)}
        />
        <TextField
          label="Default delay (seconds)"
          type="number"
          inputMode="numeric"
          min={1}
          max={RETRY_INTERVAL_SECONDS_MAX}
          description={`Required when retries are greater than zero. The server accepts 1–${RETRY_INTERVAL_SECONDS_MAX}.`}
          registration={register(`${name}.intervalSeconds`, { valueAsNumber: true })}
          error={fieldMessage(errors, `${name}.intervalSeconds`)}
        />
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-2">
          <div>
            <p className="text-sm font-medium">Per-category rules</p>
            <p className="text-muted-foreground text-sm">
              Optional. A rule overrides the default for its own category. Delays
              use the MM:SS format, for example 05:00.
            </p>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={addRule}
            disabled={rules.length >= CATEGORY_OPTIONS.length}
          >
            <PlusIcon aria-hidden="true" />
            Add rule
          </Button>
        </div>

        {rules.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            No per-category rules. Every retryable failure uses the defaults above.
          </p>
        ) : null}

        {rules.map((rule, index) => {
          const used = new Set(
            rules.filter((_, i) => i !== index).map((other) => other.category),
          );
          const needsDelay = rule.enabled && Number(rule.maxRetries) > 0;
          return (
            <div
              key={`${rule.category}-${index}`}
              className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[1fr_auto_1fr_1fr_auto]"
            >
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={`${name}-rule-${index}-category`}>
                  Failure category
                </Label>
                <Select
                  value={rule.category}
                  onValueChange={(value) =>
                    update(
                      rules.map((other, i) =>
                        i === index ? { ...other, category: value } : other,
                      ),
                    )
                  }
                >
                  <SelectTrigger id={`${name}-rule-${index}-category`} className="w-full">
                    <SelectValue placeholder="Select a category" />
                  </SelectTrigger>
                  <SelectContent>
                    {CATEGORY_OPTIONS.filter(
                      (option) =>
                        option.value === rule.category || !used.has(option.value),
                    ).map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="flex items-end pb-1">
                <label className="inline-flex cursor-pointer items-center gap-1.5 text-sm">
                  <input
                    type="checkbox"
                    checked={rule.enabled}
                    onChange={(event) =>
                      update(
                        rules.map((other, i) =>
                          i === index
                            ? { ...other, enabled: event.target.checked }
                            : other,
                        ),
                      )
                    }
                    className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                  />
                  <span>Enabled</span>
                </label>
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor={`${name}-rule-${index}-retries`}>Retries</Label>
                <Input
                  id={`${name}-rule-${index}-retries`}
                  type="number"
                  inputMode="numeric"
                  min={0}
                  max={10}
                  value={Number.isFinite(rule.maxRetries) ? rule.maxRetries : 0}
                  onChange={(event) =>
                    update(
                      rules.map((other, i) =>
                        i === index
                          ? { ...other, maxRetries: event.target.valueAsNumber }
                          : other,
                      ),
                    )
                  }
                />
              </div>

              {needsDelay ? (
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor={`${name}-rule-${index}-delay`}>Delay (MM:SS)</Label>
                  <Input
                    id={`${name}-rule-${index}-delay`}
                    placeholder="05:00"
                    value={rule.retryDelay}
                    onChange={(event) =>
                      update(
                        rules.map((other, i) =>
                          i === index
                            ? { ...other, retryDelay: event.target.value }
                            : other,
                        ),
                      )
                    }
                    aria-invalid={
                      fieldMessage(errors, `${name}.rules.${index}.retryDelay`)
                        ? true
                        : undefined
                    }
                  />
                  {fieldMessage(errors, `${name}.rules.${index}.retryDelay`) ? (
                    <p role="alert" className="text-destructive text-sm font-medium">
                      {fieldMessage(errors, `${name}.rules.${index}.retryDelay`)}
                    </p>
                  ) : null}
                </div>
              ) : (
                <p className="self-end pb-2 text-muted-foreground text-sm">
                  No delay needed.
                </p>
              )}

              <div className="flex items-end pb-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={`Remove rule for ${CATEGORY_LABEL[rule.category as ConfigurableCategory] ?? rule.category}`}
                  onClick={() => update(rules.filter((_, i) => i !== index))}
                >
                  <Trash2Icon aria-hidden="true" />
                </Button>
              </div>
            </div>
          );
        })}

        {fieldMessage(errors, `${name}.rules`) ? (
          <p role="alert" className="text-destructive text-sm font-medium">
            {fieldMessage(errors, `${name}.rules`)}
          </p>
        ) : null}
      </div>
    </FieldSet>
  );
}

/** Normalises whatever the form holds into editable rows. */
function toRows(value: unknown): RetryRuleRow[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter(
      (row): row is Record<string, unknown> => typeof row === "object" && row !== null,
    )
    .map((row) => ({
      category: typeof row.category === "string" ? row.category : "NO_ANSWER",
      enabled: row.enabled !== false,
      maxRetries: typeof row.maxRetries === "number" ? row.maxRetries : 0,
      retryDelay: typeof row.retryDelay === "string" ? row.retryDelay : "",
    }));
}

/** Reads a nested react-hook-form error message by dotted path. */
function fieldMessage(
  errors: Record<string, unknown>,
  path: string,
): string | undefined {
  let cursor: unknown = errors;
  for (const segment of path.split(".")) {
    if (typeof cursor !== "object" || cursor === null) return undefined;
    cursor = (cursor as Record<string, unknown>)[segment];
  }
  if (typeof cursor !== "object" || cursor === null) return undefined;
  const message = (cursor as { message?: unknown }).message;
  return typeof message === "string" ? message : undefined;
}
