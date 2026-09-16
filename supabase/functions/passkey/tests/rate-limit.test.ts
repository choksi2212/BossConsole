/**
 * Tests for the /auth/challenge rate limiter (BossConsole#768).
 *
 * `now` is injectable, so the window is advanced without sleeping.
 */

import { assertEquals } from "jsr:@std/assert"
import { rateLimit, clientKey } from "../utils/rate-limit.ts"

Deno.test("rateLimit - allows up to the limit within the window", () => {
  const key = "authchallenge:1.2.3.4"
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, true)
  }
})

Deno.test("rateLimit - trips on the next call in the same window and reports retry-after", () => {
  const key = "authchallenge:5.6.7.8"
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, true)
  }
  const blocked = rateLimit(key, 20, 3600, 1_000_000)
  assertEquals(blocked.allowed, false)
  assertEquals(blocked.retryAfterSeconds > 0, true)
})

Deno.test("rateLimit - a new window resets the count", () => {
  const key = "authchallenge:9.9.9.9"
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, true)
  }
  assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, false)
  // 3600s later the window has expired
  assertEquals(rateLimit(key, 20, 3600, 1_000_000 + 3600_000).allowed, true)
})

Deno.test("rateLimit - distinct clients get distinct budgets", () => {
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit("authchallenge:1.1.1.1", 20, 3600, 1_000_000).allowed, true)
  }
  assertEquals(rateLimit("authchallenge:1.1.1.1", 20, 3600, 1_000_000).allowed, false)
  assertEquals(rateLimit("authchallenge:2.2.2.2", 20, 3600, 1_000_000).allowed, true)
})

Deno.test("clientKey - prefers the first forwarded-for hop, falls back to connecting headers", () => {
  const forwarded = new Headers({ "x-forwarded-for": "3.3.3.3, 4.4.4.4" })
  assertEquals(clientKey(forwarded), "3.3.3.3")

  const cf = new Headers({ "cf-connecting-ip": "5.5.5.5" })
  assertEquals(clientKey(cf), "5.5.5.5")

  const real = new Headers({ "x-real-ip": "6.6.6.6" })
  assertEquals(clientKey(real), "6.6.6.6")

  assertEquals(clientKey(new Headers()), "unknown")
})
