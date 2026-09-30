// Sign in with Apple's REST API: the client secret (an ES256 JWT), exchanging an authorization code
// for a refresh token, and revoking that token.
// https://developer.apple.com/documentation/sign_in_with_apple/generate_and_validate_tokens
// https://developer.apple.com/documentation/sign_in_with_apple/revoke_tokens

export const APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";
export const APPLE_REVOKE_URL = "https://appleid.apple.com/auth/revoke";

/** From the Apple Developer account (see docs/MAP_SETUP.md). Never in the repo: function secrets only. */
export interface AppleConfig {
  teamId: string;
  keyId: string;
  /** The .p8 key file's contents (PKCS#8 PEM). Literal "\n" sequences are accepted too. */
  privateKey: string;
  /** The app's bundle ID (native Sign in with Apple), e.g. com.pawpixel.app. */
  clientId: string;
}

export class AppleError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function base64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const utf8 = (s: string) => new TextEncoder().encode(s);

export async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/\\n/g, "\n").replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

/**
 * The client secret Apple asks for instead of a password: a JWT signed with the Sign in with Apple
 * key. WebCrypto's ECDSA signature is already the raw r||s form that JWS (ES256) uses.
 */
export async function clientSecret(config: AppleConfig, nowSeconds: number): Promise<string> {
  const header = { alg: "ES256", kid: config.keyId, typ: "JWT" };
  const claims = {
    iss: config.teamId,
    iat: nowSeconds,
    exp: nowSeconds + 300, // used at once; Apple allows up to 6 months
    aud: "https://appleid.apple.com",
    sub: config.clientId,
  };
  const input = `${base64url(utf8(JSON.stringify(header)))}.${base64url(utf8(JSON.stringify(claims)))}`;
  const key = await importPrivateKey(config.privateKey);
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, utf8(input));
  return `${input}.${base64url(new Uint8Array(signature))}`;
}

async function post(fetchFn: typeof fetch, url: string, form: Record<string, string>): Promise<Response> {
  return await fetchFn(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form).toString(),
  });
}

/** Exchanges the app's one-time authorization code (valid 5 minutes) for a long-lived refresh token. */
export async function exchangeCode(config: AppleConfig, code: string, fetchFn: typeof fetch, nowSeconds: number): Promise<string> {
  const r = await post(fetchFn, APPLE_TOKEN_URL, {
    client_id: config.clientId,
    client_secret: await clientSecret(config, nowSeconds),
    code,
    grant_type: "authorization_code",
  });
  const text = await r.text();
  if (!r.ok) throw new AppleError(r.status, `Apple refused the code (${r.status}): ${text.slice(0, 200)}`);
  const refreshToken = JSON.parse(text).refresh_token;
  if (typeof refreshToken !== "string" || !refreshToken) throw new AppleError(502, "Apple returned no refresh token");
  return refreshToken;
}

/** Revokes the refresh token, which ends the app's Sign in with Apple link for that Apple ID. */
export async function revokeToken(config: AppleConfig, refreshToken: string, fetchFn: typeof fetch, nowSeconds: number): Promise<void> {
  const r = await post(fetchFn, APPLE_REVOKE_URL, {
    client_id: config.clientId,
    client_secret: await clientSecret(config, nowSeconds),
    token: refreshToken,
    token_type_hint: "refresh_token",
  });
  if (!r.ok) throw new AppleError(r.status, `Apple refused the revocation (${r.status}): ${(await r.text()).slice(0, 200)}`);
}
