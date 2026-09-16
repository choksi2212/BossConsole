import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"

Deno.test("browse masks unexpected database exceptions at the HTTP boundary", async () => {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const client = {
    from() { throw new Error("private schema and connection details") },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", browse)
  const response = await app.request("/list")
  assertEquals(response.status, 500)
  assertEquals(await response.json(), { error: "Internal server error" })
})
