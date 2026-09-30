// Sign in with Apple token storage and revocation for account deletion (App Store Review
// Guideline 5.1.1(v)). Setup: docs/MAP_SETUP.md, section 3. Logic and tests: handler.ts, handler_test.ts.
import { createHandler } from "./handler.ts";

Deno.serve(createHandler({ env: (name) => Deno.env.get(name), fetch: globalThis.fetch, nowMs: Date.now }));
