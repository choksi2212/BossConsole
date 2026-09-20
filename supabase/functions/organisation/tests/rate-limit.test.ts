/**
 * The rate limiter, including the failure mode a naive cap would have.
 */

import { assert, assertEquals } from "@std/assert"
import { clientKey, rateLimit, resetRateLimits } from "../utils/rate-limit.ts"

Deno.test("requests are allowed up to the limit and refused after", () => {
  resetRateLimits()
  const now = 1_000_000

  for (let i = 0; i < 5; i++) {
    assertEquals(rateLimit("k", 5, 60, now).allowed, true, `request ${i + 1} should pass`)
  }

  const refused = rateLimit("k", 5, 60, now)
  assertEquals(refused.allowed, false)
  assert(refused.retryAfterSeconds > 0 && refused.retryAfterSeconds <= 60)
})

Deno.test("the window resets", () => {
  resetRateLimits()
  const now = 1_000_000
  for (let i = 0; i < 5; i++) rateLimit("k", 5, 60, now)
  assertEquals(rateLimit("k", 5, 60, now).allowed, false)
  assertEquals(rateLimit("k", 5, 60, now + 60_001).allowed, true)
})

Deno.test("keys are independent", () => {
  resetRateLimits()
  const now = 1_000_000
  for (let i = 0; i < 5; i++) rateLimit("a", 5, 60, now)
  assertEquals(rateLimit("a", 5, 60, now).allowed, false)
  assertEquals(rateLimit("b", 5, 60, now).allowed, true)
})

Deno.test("a flood of distinct keys does not disable the limiter", () => {
  resetRateLimits()
  const now = 1_000_000

  // Every key is fresh and nothing has expired, so the eviction pass frees
  // nothing. Without the clear-on-full fallback the map would sit at MAX_KEYS
  // and every subsequent key would bypass the limiter entirely.
  for (let i = 0; i < 10_050; i++) rateLimit(`flood-${i}`, 1, 60, now)

  const victim = "still-limited"
  assertEquals(rateLimit(victim, 1, 60, now).allowed, true)
  assertEquals(rateLimit(victim, 1, 60, now).allowed, false)
})

Deno.test("clientKey uses the rightmost XFF entry (the trusted proxy hop), not the spoofable leftmost", () => {
  // Attacker rotates the leftmost entry every request; the real client is
  // appended by the Supabase edge on the right. The limiter must key on the
  // rightmost so all of these collapse to one bucket.
  assertEquals(
    clientKey(new Headers({ "x-forwarded-for": "203.0.113.5, 70.41.3.18" })),
    "70.41.3.18",
  )
  assertEquals(
    clientKey(new Headers({ "x-forwarded-for": "1.1.1.1, 70.41.3.18" })),
    "70.41.3.18",
  )
})

Deno.test("clientKey prefers cf-connecting-ip when present (Cloudflare-set, caller cannot spoof)", () => {
  // Even if XFF says one thing, cf-connecting-ip is the Cloudflare-observed
  // address and wins. Spoofable XFF falls through.
  assertEquals(
    clientKey(new Headers({
      "cf-connecting-ip": "203.0.113.9",
      "x-forwarded-for": "1.1.1.1, 70.41.3.18",
    })),
    "203.0.113.9",
  )
  assertEquals(
    clientKey(new Headers({ "cf-connecting-ip": "203.0.113.9" })),
    "203.0.113.9",
  )
})

Deno.test("clientKey trims whitespace and falls through to x-real-ip, then to 'unknown'", () => {
  assertEquals(
    clientKey(new Headers({ "x-forwarded-for": "  10.0.0.7  ,  10.0.0.8  " })),
    "10.0.0.8",
  )
  assertEquals(
    clientKey(new Headers({ "x-real-ip": "10.0.0.99" })),
    "10.0.0.99",
  )
  assertEquals(clientKey(new Headers()), "unknown")
})

Deno.test("clientKey honours skipTrustedHops and skips the rightmost N hops", () => {
  // One extra trusted hop in front of the Supabase edge -> skip 1 from the
  // right. The remaining chain's rightmost is the real client.
  assertEquals(
    clientKey(
      new Headers({ "x-forwarded-for": "203.0.113.5, 70.41.3.18, 10.0.0.1" }),
      1,
    ),
    "70.41.3.18",
  )
  // Skip 2 from the right.
  assertEquals(
    clientKey(
      new Headers({ "x-forwarded-for": "203.0.113.5, 70.41.3.18, 10.0.0.1" }),
      2,
    ),
    "203.0.113.5",
  )
})

Deno.test("clientKey fails open to the rightmost when the chain is shorter than the skip count", () => {
  // Asking to skip 3 hops when the header only has 2 entries: fall back to
  // the rightmost (most-trusted observation) rather than to a leftmost
  // entry that is most likely attacker-controlled.
  assertEquals(
    clientKey(
      new Headers({ "x-forwarded-for": "203.0.113.5, 70.41.3.18" }),
      3,
    ),
    "70.41.3.18",
  )
})

Deno.test("clientKey puts every XFF-rotating attacker behind one bucket", () => {
  // The fix for #974: each request with a fresh leftmost value but the same
  // appended real client must land in the same rate-limit bucket, so the
  // limiter actually trips.
  const realClient = "70.41.3.18"
  const bucket = clientKey(new Headers({ "x-forwarded-for": `1.1.1.1, ${realClient}` }))
  for (let i = 0; i < 10; i++) {
    const rotated = clientKey(
      new Headers({ "x-forwarded-for": `10.0.0.${i}, ${realClient}` }),
    )
    assertEquals(rotated, bucket)
  }
})

Deno.test("clientKey ignores empty / whitespace-only forwarded entries", () => {
  assertEquals(
    clientKey(new Headers({ "x-forwarded-for": "  , 10.0.0.8  ,  " })),
    "10.0.0.8",
  )
})
