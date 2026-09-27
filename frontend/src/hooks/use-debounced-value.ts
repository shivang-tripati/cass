"use client";

import { useEffect, useState } from "react";

/**
 * Returns `value` only after it has been stable for `delayMs`.
 * The state update happens inside the timeout callback (not synchronously
 * in the effect body), matching react-hooks guidelines.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
