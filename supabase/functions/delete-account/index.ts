import { createClient } from "npm:@supabase/supabase-js@2.49.4";

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response(null, { status: 405 });
  const access = request.headers.get("Authorization")?.match(/^Bearer (.+)$/i)?.[1];
  if (!access) return Response.json({ error: "Sign-in required." }, { status: 401 });

  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false, autoRefreshToken: false } },
  );
  const { data, error } = await admin.auth.getUser(access);
  if (error || !data.user) return Response.json({ error: "Sign-in required." }, { status: 401 });

  const removed = await admin.auth.admin.deleteUser(data.user.id);
  if (removed.error) return Response.json({ error: "Account deletion failed. Try again." }, { status: 500 });
  return Response.json({ deleted: true });
});
