import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * F1 — minimal test foundation.
 *
 * Scope is deliberately narrow: `environment: "node"`, no DOM, no component
 * rendering, no mocking library. F0 found the project had NO test runner at
 * all, and the F1 brief says not to add a large framework without
 * justification. The justification for adding one at all is that the contract
 * layer F1 changed is pure, synchronous and exactly the kind of logic that
 * silently drifts: envelope unwrapping, error normalization, capability
 * decisions, operating-scope derivation and the campaign configuration
 * validators. Those are testable with zero infrastructure.
 *
 * Component and route tests need jsdom + Testing Library, which is a larger
 * addition and is deferred — see the F1 document's "Remaining work" section.
 * Next.js and the `@/` path alias are deliberately NOT wired in here: nothing
 * under test imports the framework, and a full Next plugin would slow every run
 * for no benefit.
 */
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
    // `src/lib` is plain TypeScript with no framework imports, so the path
    // alias is resolved directly rather than through a bundler plugin.
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
