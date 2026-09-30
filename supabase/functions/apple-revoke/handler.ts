// The apple-revoke Edge Function (index.ts serves it). POST with the owner's session:
//   {"action":"store","code":"<authorization code>"}  right after Sign in with Apple: swaps the one-time
//        code for a refresh token and keeps it in public.apple_tokens (never readable by the app).
//   {"action":"delete_account"}  deletes the owner's account (App Store Review Guideline 5.1.1(v)):
//        first revokes the Apple token at https://appleid.apple.com/auth/revoke, then deletes the
//        account with the same delete_account() the app used before (everything cascades from it).
// Support (docs/DELETE_ACCOUNT.md), with the service role key only: {"action":"revoke","user_id":"..."}.
//
// Until the Apple secrets are set (CI, local servers, a new project), nothing breaks: "store" keeps
// nothing and "delete_account" still deletes; both log a note that revocation is skipped.
import { AppleConfig, AppleError, exchangeCode, revokeToken } from "./apple.ts";

export interface Deps {
  env: (name: string) => string | undefined;
  fetch: typeof fetch;
  nowMs: () => number;
  log?: (line: string) => void;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** The app's bundle ID is the client ID for native Sign in with Apple. */
export const DEFAULT_APPLE_CLIENT_ID = "com.pawpixel.app";

export const NOT_CONFIGURED = "Sign in with Apple revocation isn't set up (APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_PRIVATE_KEY)";

export function appleConfig(env: Deps["env"]): AppleConfig | null {
  const teamId = env("APPLE_TEAM_ID")?.trim(), keyId = env("APPLE_KEY_ID")?.trim(), privateKey = env("APPLE_PRIVATE_KEY");
  const clientId = env("APPLE_CLIENT_ID")?.trim() || DEFAULT_APPLE_CLIENT_ID;
  return teamId && keyId && privateKey?.trim() ? { teamId, keyId, privateKey, clientId } : null;
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
  const log = deps.log ?? ((line: string) => console.log(line));

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

  async function storedToken(userId: string): Promise<string | null> {
    const rows = await (await table("GET", `?user_id=eq.${encodeURIComponent(userId)}&select=refresh_token`)).json();
    const refreshToken = rows?.[0]?.refresh_token;
    return typeof refreshToken === "string" && refreshToken ? refreshToken : null;
  }

  async function store(config: AppleConfig | null, user: User, code: unknown): Promise<Response> {
    if (!user.providers.includes("apple")) return json(400, { error: "This account didn't sign in with Apple" });
    if (typeof code !== "string" || code.length === 0 || code.length > 2048) return json(400, { error: "Missing authorization code" });
    if (!config) {
      log(`apple-revoke: ${NOT_CONFIGURED}; not keeping a token for ${user.id}.`);
      return json(200, { stored: false, reason: "not configured" });
    }
    const refreshToken = await exchangeCode(config, code, deps.fetch, nowSeconds());
    await table("POST", "", { user_id: user.id, refresh_token: refreshToken, updated_at: new Date(deps.nowMs()).toISOString() },
      "resolution=merge-duplicates,return=minimal");
    return json(200, { stored: true });
  }

  /** Revokes at Apple and forgets the token. Throws if Apple refuses (the token is kept). */
  async function revoke(config: AppleConfig, userId: string): Promise<"revoked" | "no token on file"> {
    const refreshToken = await storedToken(userId);
    if (!refreshToken) return "no token on file";
    await revokeToken(config, refreshToken, deps.fetch, nowSeconds());
    await table("DELETE", `?user_id=eq.${encodeURIComponent(userId)}`);
    return "revoked";
  }

  /**
   * Revocation is best effort: the owner asked for the account to go, and it goes even if Apple is
   * down or the secrets aren't set (the log says so). Deleting uses the owner's own session and the
   * delete_account() RPC, so the app and this function delete in exactly the same way.
   */
  async function deleteAccount(config: AppleConfig | null, user: User, token: string): Promise<Response> {
    let revocation: string;
    if (!config) {
      revocation = "skipped: not configured";
      if (user.providers.includes("apple")) log(`apple-revoke: ${NOT_CONFIGURED}; deleting ${user.id} without revoking at Apple.`);
    } else {
      try {
        revocation = await revoke(config, user.id);
      } catch (e) {
        revocation = "failed";
        log(`apple-revoke: revoking ${user.id} at Apple failed, deleting the account anyway: ${e instanceof Error ? e.message : e}`);
      }
    }
    const r = await deps.fetch(`${supabaseUrl}/rest/v1/rpc/delete_account`, {
      method: "POST",
      headers: { apikey: anonKey, Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: "{}",
    });
    if (!r.ok) throw new Error(`delete_account failed (${r.status}): ${(await r.text()).slice(0, 200)}`);
    return json(200, { deleted: true, revoked: revocation === "revoked", revocation });
  }

  return async (req: Request): Promise<Response> => {
    if (req.method !== "POST") return json(405, { error: "POST only" });
    if (!supabaseUrl || !serviceKey) return json(500, { error: "Function environment is incomplete" });
    const config = appleConfig(deps.env);

    const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    if (!token) return json(401, { error: "Sign in first" });
    let body: { action?: unknown; code?: unknown; user_id?: unknown };
    try {
      body = await req.json();
    } catch {
      return json(400, { error: "Body must be JSON" });
    }
    if (typeof body !== "object" || body === null) return json(400, { error: "Body must be a JSON object" });

    try {
      // Support (docs/DELETE_ACCOUNT.md): the service role key may revoke for any account, before
      // deleting it in the dashboard.
      if (token === serviceKey) {
        if (body.action !== "revoke" || typeof body.user_id !== "string") return json(400, { error: "Service calls: revoke with a user_id" });
        if (!config) return json(503, { error: NOT_CONFIGURED });
        return json(200, { revoked: (await revoke(config, body.user_id)) === "revoked" });
      }
      const user = await userFor(token);
      if (!user) return json(401, { error: "Sign in first" });
      if (body.action === "store") return await store(config, user, body.code);
      if (body.action === "delete_account") return await deleteAccount(config, user, token);
      return json(400, { error: "Unknown action" });
    } catch (e) {
      const status = e instanceof AppleError ? 502 : 500;
      log(`apple-revoke: ${e instanceof Error ? e.message : e}`);
      return json(status, { error: e instanceof AppleError ? "Apple refused the request" : "Server error" });
    }
  };
}
