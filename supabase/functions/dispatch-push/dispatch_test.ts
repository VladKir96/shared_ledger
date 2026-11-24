import { dispatchPush } from "./dispatch.ts";

function expect(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
}

function decodeSegment(segment: string): Uint8Array<ArrayBuffer> {
  const base64 = segment.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(segment.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
}

Deno.test("dispatcher protects the queue, sends only an ID, and classifies provider responses", async () => {
  const savedFetch = globalThis.fetch;
  const names = ["PUSH_DISPATCH_SECRET", "FCM_SERVICE_ACCOUNT_JSON", "SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY"];
  const saved = new Map(names.map((name) => [name, Deno.env.get(name)]));
  const secret = "a".repeat(64);
  const key = await crypto.subtle.generateKey({
    name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, hash: "SHA-256", publicExponent: new Uint8Array([1, 0, 1]),
  }, true, ["sign", "verify"]);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", key.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----`;
  Deno.env.set("PUSH_DISPATCH_SECRET", secret);
  Deno.env.set("FCM_SERVICE_ACCOUNT_JSON", JSON.stringify({
    project_id: "local-fixture", client_email: "fixture@example.test", private_key: pem,
  }));
  Deno.env.set("SUPABASE_URL", "https://supabase.example.test");
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "local-only-test");
  let calls = 0;
  const finishes: Record<string, unknown>[] = [];
  globalThis.fetch = (async (input: string, init: RequestInit) => {
    calls++;
    const url = new URL(input);
    if (url.host === "oauth2.googleapis.com") {
      if (!(init.body instanceof URLSearchParams)) throw new Error("OAuth request was not form encoded");
      expect(init.method === "POST" && url.pathname === "/token", "Unexpected OAuth exchange endpoint");
      expect(init.body.get("grant_type") === "urn:ietf:params:oauth:grant-type:jwt-bearer", "Wrong OAuth grant");
      const [header, payload, signature, extra] = init.body.get("assertion")?.split(".") ?? [];
      if (!header || !payload || !signature || extra !== undefined) throw new Error("OAuth JWT missing");
      const decoder = new TextDecoder();
      const claims = JSON.parse(decoder.decode(decodeSegment(payload)));
      expect(JSON.parse(decoder.decode(decodeSegment(header))).alg === "RS256", "Wrong assertion algorithm");
      expect(claims.iss === "fixture@example.test" && claims.aud === url.href &&
        claims.scope === "https://www.googleapis.com/auth/firebase.messaging" && claims.exp - claims.iat === 3600,
        "Unexpected OAuth assertion claims");
      expect(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key.publicKey, decodeSegment(signature),
        new TextEncoder().encode(`${header}.${payload}`)), "Invalid OAuth assertion signature");
      return Promise.resolve(Response.json({ access_token: "local-access", expires_in: 3600 }));
    }
    const body = JSON.parse(String(init.body));
    if (url.host === "fcm.googleapis.com") {
      expect(url.pathname === "/v1/projects/local-fixture/messages:send" && init.method === "POST" &&
        new Headers(init.headers).get("Authorization") === "Bearer local-access", "Wrong authenticated FCM request");
      expect(Object.keys(body.message.data).join() === "ledger_event", "Unexpected data in FCM payload");
      expect(!body.message.notification, "FCM contained notification details");
      if (body.message.fid === "token-a") {
        return Promise.resolve(Response.json({}, { status: 429, headers: { "Retry-After": "120" } }));
      }
      return Promise.resolve(Response.json({ error: { details: [{ errorCode: "UNREGISTERED" }] } }, { status: 404 }));
    }
    if (url.pathname.endsWith("/ledger_push_claim")) {
      return Promise.resolve(Response.json(["a", "b", "c"].map((id) => ({ event_id: id, device_id: id, lease: id }))));
    }
    if (url.pathname.endsWith("/ledger_push_target")) {
      return Promise.resolve(Response.json(body.p_event === "c" ? null : `token-${body.p_event}`));
    }
    if (url.pathname.endsWith("/ledger_push_finish")) {
      finishes.push(body);
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    throw new Error("Unexpected HTTP call");
  }) as typeof fetch;
  try {
    const denied = await dispatchPush(new Request("https://worker.example.test", { method: "POST" }));
    expect(denied.status === 403 && calls === 0, "Unauthorized request reached a provider");
    const request = new Request("https://worker.example.test", { method: "POST", headers: { "x-ledger-dispatch": secret } });
    const result = await (await dispatchPush(request)).json();
    expect(result.retry === 1 && result.skipped === 1 && result.unregistered === 1, "Wrong delivery outcomes");
    expect(finishes.some((r) => r.p_result === "retry" && r.p_retry_seconds === 120 && r.p_provider_status === 429),
      "Rate limit details were ignored");
    expect(finishes.some((r) => r.p_result === "unregistered" && r.p_registration === "token-b"), "Invalid token not deactivated");
  } finally {
    globalThis.fetch = savedFetch;
    for (const [name, value] of saved) {
      if (value === undefined) Deno.env.delete(name); else Deno.env.set(name, value);
    }
  }
});
