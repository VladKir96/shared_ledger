type Lease = { event_id: string; device_id: string; lease: string };
type ServiceAccount = { project_id: string; client_email: string; private_key: string };
type Result = "sent" | "skipped" | "retry" | "permanent" | "unregistered";
const encoder = new TextEncoder();
const oauthUrl = "https://oauth2.googleapis.com/token";
let cached: { token: string; expires: number } | undefined;

function base64url(value: Uint8Array): string {
  return btoa(String.fromCharCode(...value)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

async function authorized(request: Request): Promise<boolean> {
  const expected = Deno.env.get("PUSH_DISPATCH_SECRET");
  const given = request.headers.get("x-ledger-dispatch");
  if (!expected || expected.length < 32 || !given) return false;
  const hashes = await Promise.all([expected, given].map(async (s) =>
    new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(s)))));
  let different = 0;
  for (let i = 0; i < hashes[0].length; i++) different |= hashes[0][i] ^ hashes[1][i];
  return different === 0;
}

async function oauth(account: ServiceAccount): Promise<string> {
  if (cached && cached.expires > Date.now() + 60_000) return cached.token;
  const pem = account.private_key.replace(/-----[^-]+-----|\s/g, "");
  const key = await crypto.subtle.importKey("pkcs8", Uint8Array.from(atob(pem), (s) => s.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const time = Math.floor(Date.now() / 1000);
  const claims = { iss: account.client_email, aud: oauthUrl,
    scope: "https://www.googleapis.com/auth/firebase.messaging", iat: time, exp: time + 3600 };
  const encoded = [{ alg: "RS256", typ: "JWT" }, claims].map((value) =>
    base64url(encoder.encode(JSON.stringify(value)))).join(".");
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(encoded)));
  const response = await fetch(oauthUrl, { method: "POST", signal: AbortSignal.timeout(8_000),
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: encoded + "." + base64url(signature) }) });
  if (!response.ok) throw new Error("FCM authentication failed");
  const value = await response.json();
  if (typeof value.access_token !== "string" || !Number.isFinite(value.expires_in)) throw new Error("Invalid OAuth response");
  cached = { token: value.access_token, expires: Date.now() + value.expires_in * 1000 };
  return cached.token;
}

async function rpc<T>(name: string, body: Record<string, unknown> = {}): Promise<T> {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("Supabase worker credentials are missing");
  const response = await fetch(`${url}/rest/v1/rpc/${name}`, {
    method: "POST", signal: AbortSignal.timeout(8_000),
    headers: { apikey: key, Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error("Push queue request failed");
  const text = await response.text();
  return (text ? JSON.parse(text) : null) as T;
}

function retrySeconds(header: string | null): number {
  if (!header) return 0;
  const seconds = /^\d+$/.test(header) ? Number(header) : (Date.parse(header) - Date.now()) / 1000;
  return Number.isFinite(seconds) ? Math.ceil(Math.max(0, Math.min(seconds, 21600))) : 0;
}

async function deliver(lease: Lease, project: string, access: string): Promise<Result> {
  const params = { p_event: lease.event_id, p_device: lease.device_id, p_lease: lease.lease };
  const registration = await rpc<string | null>("ledger_push_target", params);
  let result: Result = "skipped";
  let retry = 0;
  let providerStatus: number | null = null;
  if (registration) {
    try {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${project}/messages:send`, {
        method: "POST", signal: AbortSignal.timeout(8_000),
        headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
        body: JSON.stringify({ message: { fid: registration, data: { ledger_event: lease.event_id },
          android: { priority: "HIGH", ttl: "86400s" } } }),
      });
      providerStatus = response.status;
      if (response.ok) {
        result = "sent";
        await response.body?.cancel();
      } else {
        const error = await response.json().catch(() => null);
        const unregistered = Array.isArray(error?.error?.details) &&
          error.error.details.some((detail: { errorCode?: string }) => detail.errorCode === "UNREGISTERED");
        const transient = response.status >= 500 || [401, 403, 429].includes(response.status);
        result = unregistered ? "unregistered" : transient ? "retry" : "permanent";
        retry = retrySeconds(response.headers.get("Retry-After"));
        if (response.status === 401) cached = undefined;
      }
    } catch {
      result = "retry";
    }
  }
  await rpc("ledger_push_finish", { ...params, p_result: result,
    p_registration: result === "unregistered" ? registration : null, p_retry_seconds: retry, p_provider_status: providerStatus });
  return result;
}

export async function dispatchPush(request: Request): Promise<Response> {
  if (request.method !== "POST") return new Response(null, { status: 405 });
  if (!await authorized(request)) return new Response(null, { status: 403 });
  try {
    const account: ServiceAccount = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "null");
    if (!account?.project_id?.match(/^[a-z0-9-]{1,80}$/) || !account.private_key || !account.client_email) {
      return new Response("FCM is not configured", { status: 503 });
    }
    const access = await oauth(account);
    const claimed = await rpc<Lease[]>("ledger_push_claim");
    const summary: Partial<Record<Result | "lease_error", number>> = {};
    // A small batch of concurrent requests, with no tokens or financial information in diagnostics.
    for (let i = 0; i < claimed.length; i += 5) {
      const results = await Promise.allSettled(claimed.slice(i, i + 5).map((lease) => deliver(lease, account.project_id, access)));
      for (const result of results) {
        const key = result.status === "fulfilled" ? result.value : "lease_error";
        summary[key] = (summary[key] ?? 0) + 1;
      }
    }
    return Response.json(summary);
  } catch {
    return new Response("Push dispatcher unavailable", { status: 503 });
  }
}
