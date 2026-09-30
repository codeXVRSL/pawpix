// The apple-revoke Edge Function (index.ts serves it). Two calls, both POST with the owner's session:
//   {"action":"store","code":"<authorization code>"}  right after Sign in with Apple: swaps the code
//        for a refresh token and keeps it in public.apple_tokens (never readable by the app).
//   {"action":"revoke"}  just before the account is deleted: revokes the token at Apple, then deletes
//        the row. Support can revoke for someone else with the service role key and "user_id".
import { AppleConfig, AppleError, exchangeCode, revokeToken } from "./apple.ts";

export interface Deps {
  env: (name: string) => string | undefined;
  fetch: typeof fetch;
  nowMs: () => number;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function appleConfig(env: Deps["env"]): AppleConfig | null {
  const teamId = env("APPLE_TEAM_ID"), keyId = env("APPLE_KEY_ID"), privateKey = env("APPLE_PRIVATE_KEY"), clientId = env("APPLE_CLIENT_ID");
  return teamId && keyId && privateKey && clientId ? { teamId, keyId, privateKey, clientId } : null;
}

interface User {
  id: string;
  providers: string[];
}

export function createHandler(deps: Deps): (req: Request) => Promise<Response> {
  const supabaseUrl = (deps.env("SUPABASE_URL") ?? "").replace(/\/$/, "");
  const serviceKey = deps.env("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const anonKey = deps.env("SUPABASE_ANON_KEY") ?? serviceKey;
  const nowSeconds = () => Math.floor(deps.nowMs() / 1000);

  /** The signed-in owner behind [token], checked by Supabase Auth (null if the session isn't valid). */
  async function userFor(token: string): Promise<User | null> {
    const r = await deps.fetch(`${supabaseUrl}/auth/v1/user`, { headers: { apikey: anonKey, Authorization: `Bearer ${token}` } });
    if (!r.ok) return null;
    const u = await r.json();
    if (typeof u?.id !== "string") return null;
    const providers: string[] = u.app_metadata?.providers ?? (u.app_metadata?.provider ? [u.app_metadata.provider] : []);
    return { id: u.id, providers };
  }

  /** PostgREST with the service role (bypasses row level security; this table has no other way in). */
  async function table(method: string, query: string, body?: unknown, prefer?: string): Promise<Response> {
    const headers: Record<string, string> = { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json" };
    if (prefer) headers.Prefer = prefer;
    const r = await deps.fetch(`${supabaseUrl}/rest/v1/apple_tokens${query}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!r.ok) throw new Error(`apple_tokens ${method} failed (${r.status}): ${(await r.text()).slice(0, 200)}`);
    return r;
  }

  async function store(config: AppleConfig, user: User, code: unknown): Promise<Response> {
    if (!user.providers.includes("apple")) return json(400, { error: "This account didn't sign in with Apple" });
    if (typeof code !== "string" || code.length === 0 || code.length > 2048) return json(400, { error: "Missing authorization code" });
    const refreshToken = await exchangeCode(config, code, deps.fetch, nowSeconds());
    await table("POST", "", { user_id: user.id, refresh_token: refreshToken, updated_at: new Date(deps.nowMs()).toISOString() },
      "resolution=merge-duplicates,return=minimal");
    return json(200, { stored: true });
  }

  async function revoke(config: AppleConfig, userId: string): Promise<Response> {
    const rows = await (await table("GET", `?user_id=eq.${encodeURIComponent(userId)}&select=refresh_token`)).json();
    const refreshToken = rows?.[0]?.refresh_token;
    if (typeof refreshToken !== "string") return json(200, { revoked: false, reason: "no Apple token on file" });
    await revokeToken(config, refreshToken, deps.fetch, nowSeconds());
    await table("DELETE", `?user_id=eq.${encodeURIComponent(userId)}`);
    return json(200, { revoked: true });
  }

  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") return json(405, { error: "POST only" });
    if (!supabaseUrl || !serviceKey) return json(500, { error: "Function environment is incomplete" });
    const config = appleConfig(deps.env);
    if (!config) return json(503, { error: "Sign in with Apple isn't set up on this server" });

    const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    if (!token) return json(401, { error: "Sign in first" });
    let body: { action?: unknown; code?: unknown; user_id?: unknown };
    try {
      body = await req.json();
    } catch {
      return json(400, { error: "Body must be JSON" });
    }

    try {
      // Support (docs/DELETE_ACCOUNT.md): the service role key may revoke for any account.
      if (token === serviceKey) {
        if (body.action !== "revoke" || typeof body.user_id !== "string") return json(400, { error: "Service calls: revoke with a user_id" });
        return await revoke(config, body.user_id);
      }
      const user = await userFor(token);
      if (!user) return json(401, { error: "Sign in first" });
      if (body.action === "store") return await store(config, user, body.code);
      if (body.action === "revoke") return await revoke(config, user.id);
      return json(400, { error: "Unknown action" });
    } catch (e) {
      const status = e instanceof AppleError ? 502 : 500;
      console.error(`apple-revoke: ${e instanceof Error ? e.message : e}`);
      return json(status, { error: e instanceof AppleError ? "Apple refused the request" : "Server error" });
    }
  };
}
