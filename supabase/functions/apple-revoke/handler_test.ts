// deno test supabase/functions/apple-revoke/
// Apple and Supabase are fakes behind an injected fetch; the Apple key is a throwaway P-256 key
// made for each test, so the client secret's ES256 signature is checked for real.
import { APPLE_REVOKE_URL, APPLE_TOKEN_URL } from "./apple.ts";
import { createHandler } from "./handler.ts";

// No test library: nothing to download in CI.
function assert(ok: unknown, message = "assertion failed"): asserts ok {
  if (!ok) throw new Error(message);
}
function assertEquals(actual: unknown, expected: unknown, message = "") {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a !== e) throw new Error(`${message} expected ${e}, got ${a}`);
}

const SUPABASE = "http://supabase.test";
const SERVICE = "service-role-key";
const NOW_MS = 1_790_000_000_000;

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body: string;
}

function b64urlDecode(s: string): Uint8Array<ArrayBuffer> {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

async function throwawayKey(): Promise<{ pem: string; publicKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const b64 = btoa(String.fromCharCode(...der)).replace(/(.{64})/g, "$1\n");
  return { pem: `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n`, publicKey: pair.publicKey };
}

/** A fake Apple + Supabase. Sessions: "sess-apple" (Apple account a1), "sess-google" (Google account g1). */
async function world(opts: { appleSecrets?: boolean; appleTokenStatus?: number; appleRevokeStatus?: number; escapedPem?: boolean } = {}) {
  const key = await throwawayKey();
  const rows = new Map<string, string>();
  const calls: Call[] = [];
  const env: Record<string, string> = { SUPABASE_URL: SUPABASE + "/", SUPABASE_SERVICE_ROLE_KEY: SERVICE, SUPABASE_ANON_KEY: "anon" };
  if (opts.appleSecrets !== false) {
    Object.assign(env, {
      APPLE_TEAM_ID: "TEAM123456",
      APPLE_KEY_ID: "KEY1234567",
      APPLE_PRIVATE_KEY: opts.escapedPem ? key.pem.replace(/\n/g, "\\n") : key.pem,
      APPLE_CLIENT_ID: "com.pawpixel.app",
    });
  }
  const users: Record<string, unknown> = {
    "sess-apple": { id: "a1", app_metadata: { provider: "apple", providers: ["apple"] } },
    "sess-google": { id: "g1", app_metadata: { provider: "google", providers: ["google"] } },
  };

  const fakeFetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const call = { url, method: init?.method ?? "GET", headers: new Headers(init?.headers), body: String(init?.body ?? "") };
    calls.push(call);
    if (url === `${SUPABASE}/auth/v1/user`) {
      const user = users[call.headers.get("Authorization")!.replace("Bearer ", "")];
      return user ? Response.json(user) : Response.json({ msg: "invalid JWT" }, { status: 401 });
    }
    if (url.startsWith(`${SUPABASE}/rest/v1/apple_tokens`)) {
      if (call.headers.get("Authorization") !== `Bearer ${SERVICE}`) return new Response("denied", { status: 401 });
      const id = new URL(url).searchParams.get("user_id")?.replace("eq.", "");
      if (call.method === "POST") {
        const row = JSON.parse(call.body);
        rows.set(row.user_id, row.refresh_token);
        return new Response(null, { status: 201 });
      }
      if (call.method === "GET") return Response.json(rows.has(id!) ? [{ refresh_token: rows.get(id!) }] : []);
      if (call.method === "DELETE") { rows.delete(id!); return new Response(null, { status: 204 }); }
    }
    if (url === APPLE_TOKEN_URL) {
      const status = opts.appleTokenStatus ?? 200;
      return status === 200
        ? Response.json({ access_token: "at", token_type: "Bearer", expires_in: 3600, refresh_token: "apple-refresh-1", id_token: "x" })
        : Response.json({ error: "invalid_grant" }, { status });
    }
    if (url === APPLE_REVOKE_URL) return new Response(null, { status: opts.appleRevokeStatus ?? 200 });
    return new Response("unexpected " + url, { status: 599 });
  }) as typeof fetch;

  const handler = createHandler({ env: (n) => env[n], fetch: fakeFetch, nowMs: () => NOW_MS });
  const send = async (session: string | null, body: unknown, method = "POST") => {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (session) headers.Authorization = `Bearer ${session}`;
    const r = await handler(new Request("http://fn.test/apple-revoke", { method, headers, body: method === "POST" ? JSON.stringify(body) : undefined }));
    return { status: r.status, body: await r.json() };
  };
  const appleCalls = () => calls.filter((c) => c.url.startsWith("https://appleid.apple.com"));
  return { key, rows, calls, appleCalls, send };
}

/** Checks the client secret Apple received: ES256 over header.claims with the Apple key, right claims. */
async function assertClientSecret(secret: string, publicKey: CryptoKey) {
  const [h, c, s] = secret.split(".");
  const header = JSON.parse(new TextDecoder().decode(b64urlDecode(h)));
  const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(c)));
  assertEquals(header, { alg: "ES256", kid: "KEY1234567", typ: "JWT" });
  assertEquals(claims.iss, "TEAM123456");
  assertEquals(claims.sub, "com.pawpixel.app");
  assertEquals(claims.aud, "https://appleid.apple.com");
  assertEquals(claims.iat, NOW_MS / 1000);
  assert(claims.exp > claims.iat && claims.exp - claims.iat <= 15_777_000, "expires within Apple's 6-month limit");
  const signature = b64urlDecode(s);
  assertEquals(signature.length, 64, "raw r||s, not DER");
  const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, signature, new TextEncoder().encode(`${h}.${c}`));
  assert(ok, "signature verifies with the Apple key's public half");
}

Deno.test("store: the authorization code becomes a refresh token kept only on the server", async () => {
  const w = await world();
  const r = await w.send("sess-apple", { action: "store", code: "auth-code-1" });
  assertEquals(r, { status: 200, body: { stored: true } });
  assertEquals(w.rows.get("a1"), "apple-refresh-1");
  const [exchange] = w.appleCalls();
  assertEquals(exchange.url, APPLE_TOKEN_URL);
  assertEquals(exchange.headers.get("Content-Type"), "application/x-www-form-urlencoded");
  const form = new URLSearchParams(exchange.body);
  assertEquals(form.get("grant_type"), "authorization_code");
  assertEquals(form.get("code"), "auth-code-1");
  assertEquals(form.get("client_id"), "com.pawpixel.app");
  await assertClientSecret(form.get("client_secret")!, w.key.publicKey);
  assert(!JSON.stringify(r.body).includes("apple-refresh-1"), "the token never goes back to the app");
});

Deno.test("revoke: Apple revokes the stored token, then the row is deleted", async () => {
  const w = await world();
  await w.send("sess-apple", { action: "store", code: "auth-code-1" });
  const r = await w.send("sess-apple", { action: "revoke" });
  assertEquals(r, { status: 200, body: { revoked: true } });
  const revoke = w.appleCalls().find((c) => c.url === APPLE_REVOKE_URL)!;
  const form = new URLSearchParams(revoke.body);
  assertEquals(form.get("token"), "apple-refresh-1");
  assertEquals(form.get("token_type_hint"), "refresh_token");
  assertEquals(form.get("client_id"), "com.pawpixel.app");
  await assertClientSecret(form.get("client_secret")!, w.key.publicKey);
  assertEquals(w.rows.size, 0);
});

Deno.test("revoke with no token on file does nothing at Apple", async () => {
  const w = await world();
  const r = await w.send("sess-apple", { action: "revoke" });
  assertEquals(r.status, 200);
  assertEquals(r.body.revoked, false);
  assertEquals(w.appleCalls().length, 0);
});

Deno.test("if Apple refuses the revocation, the token is kept and the app hears 502", async () => {
  const w = await world({ appleRevokeStatus: 400 });
  await w.send("sess-apple", { action: "store", code: "auth-code-1" });
  const r = await w.send("sess-apple", { action: "revoke" });
  assertEquals(r.status, 502);
  assertEquals(w.rows.get("a1"), "apple-refresh-1");
});

Deno.test("a code Apple refuses stores nothing", async () => {
  const w = await world({ appleTokenStatus: 400 });
  const r = await w.send("sess-apple", { action: "store", code: "used-code" });
  assertEquals(r.status, 502);
  assertEquals(w.rows.size, 0);
});

Deno.test("no session, a bad session or a Google account: refused before Apple is called", async () => {
  const w = await world();
  assertEquals((await w.send(null, { action: "revoke" })).status, 401);
  assertEquals((await w.send("sess-forged", { action: "store", code: "c" })).status, 401);
  assertEquals((await w.send("sess-google", { action: "store", code: "c" })).status, 400);
  assertEquals((await w.send("sess-apple", { action: "store" })).status, 400);
  assertEquals((await w.send("sess-apple", { action: "steal" })).status, 400);
  assertEquals((await w.send("sess-apple", {}, "GET")).status, 405);
  assertEquals(w.appleCalls().length, 0);
});

Deno.test("support can revoke by user id with the service role key, and only revoke", async () => {
  const w = await world();
  await w.send("sess-apple", { action: "store", code: "auth-code-1" });
  assertEquals((await w.send(SERVICE, { action: "store", user_id: "a1", code: "c" })).status, 400);
  const r = await w.send(SERVICE, { action: "revoke", user_id: "a1" });
  assertEquals(r, { status: 200, body: { revoked: true } });
  assertEquals(w.rows.size, 0);
});

Deno.test("without the Apple secrets the function says it isn't set up", async () => {
  const w = await world({ appleSecrets: false });
  const r = await w.send("sess-apple", { action: "store", code: "c" });
  assertEquals(r.status, 503);
  assertEquals(w.calls.length, 0);
});

Deno.test("a private key pasted with literal \\n line breaks still signs", async () => {
  const w = await world({ escapedPem: true });
  assertEquals((await w.send("sess-apple", { action: "store", code: "auth-code-1" })).status, 200);
  await assertClientSecret(new URLSearchParams(w.appleCalls()[0].body).get("client_secret")!, w.key.publicKey);
});
