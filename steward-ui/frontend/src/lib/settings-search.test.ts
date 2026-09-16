import { describe, expect, it } from "vitest"

import type { ConfigEntry, ConfigLocation, ParsedConfigDocument } from "@/lib/api"
import {
  entryHaystack,
  matchesQuery,
  searchAcross,
  setPendingJump,
  takePendingJump,
} from "@/lib/settings-search"

/**
 * steward/58: search over the settings, per service and across all of them.
 *
 * The one test that matters most in this file is "a secret's known value finds nothing" - a search
 * whose hit/no-hit can be used to guess a secret is a leak with a search box in front of it. It is
 * written the way the ticket asked: with the entry carrying the secret value anyway, as if a future
 * bug had put it there, so the assertion is about this function refusing to look rather than about
 * the wire happening not to send it.
 */

function entry(over: Partial<ConfigEntry> & { path: string; key: string }): ConfigEntry {
  return {
    label: over.key,
    comments: [],
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
    ...over,
  }
}

function location(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return { service: "steward-worker", readable: true, writable: true, ...over }
}

function document(path: string, entries: ConfigEntry[]): ParsedConfigDocument {
  return { ...location({ path, name: path }), revision: "r1", header: [], entries }
}

describe("entryHaystack / matchesQuery", () => {
  it("matches on the label", () => {
    const e = entry({ path: "worker.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "base url")).toBe(true)
  })

  it("matches on the key path", () => {
    const e = entry({ path: "worker.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "worker.base-url")).toBe(true)
  })

  it("matches on the current value", () => {
    const e = entry({ path: "worker.base-url", key: "base-url", value: "http://steward-worker:8081" })
    expect(matchesQuery(e, "8081")).toBe(true)
  })

  it("matches on the explanation text", () => {
    const e = entry({
      path: "limits.max-attempts",
      key: "max-attempts",
      explanation: "How many times a failed job is retried before it is given up on.",
    })
    expect(matchesQuery(e, "given up")).toBe(true)
  })

  it("matches on the mechanical comments when there is no schema explanation", () => {
    const e = entry({ path: "port", key: "port", comments: ["Mechanical, no schema wrote this file."] })
    expect(matchesQuery(e, "mechanical")).toBe(true)
  })

  it("is case-insensitive", () => {
    const e = entry({ path: "worker.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "BASE URL")).toBe(true)
  })

  it("finds nothing for an empty query", () => {
    const e = entry({ path: "worker.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "   ")).toBe(false)
  })

  it("excludes a MAP heading - it renders nowhere past two levels, so a hit on it would jump nowhere", () => {
    const e = entry({ path: "worker.limits.retry", key: "retry", label: "Retry", kind: "MAP" })
    expect(matchesQuery(e, "retry")).toBe(false)
  })

  // --- the one that matters most --------------------------------------------------------------

  it("RED, then fixed: a secret's known value must never be findable by that value", () => {
    // Shaped as if a future bug sent the value anyway - `secret: true` AND a `value` present, which
    // the wire contract in lib/api.ts says never happens together. The guard has to hold on its own,
    // not by leaning on the backend never making this mistake.
    const token = "MTA1NzE4.super-secret-discord-token"
    const secretEntry = entry({
      path: "discord.bot-token",
      key: "bot-token",
      label: "Bot token",
      secret: true,
      value: token,
    })

    expect(matchesQuery(secretEntry, token)).toBe(false)
    expect(entryHaystack(secretEntry)).not.toContain(token.toLowerCase())
  })

  it("still finds a secret entry by its label or path - only the value is excluded", () => {
    const secretEntry = entry({
      path: "discord.bot-token",
      key: "bot-token",
      label: "Bot token",
      secret: true,
      value: "irrelevant-if-leaked-would-be-bad",
    })
    expect(matchesQuery(secretEntry, "bot token")).toBe(true)
    expect(matchesQuery(secretEntry, "discord.bot-token")).toBe(true)
  })

  it("does not match a secret's list items either", () => {
    const secretEntry = entry({
      path: "discord.webhooks",
      key: "webhooks",
      label: "Webhooks",
      kind: "LIST",
      secret: true,
      items: ["https://discord.com/api/webhooks/leak-me-not"],
    })
    expect(matchesQuery(secretEntry, "leak-me-not")).toBe(false)
  })
})

describe("searchAcross", () => {
  it("pairs a hit with the file it lives in, across several files", () => {
    const worker = document("steward-worker/steward.yml", [
      entry({ path: "worker.base-url", key: "base-url", value: "http://steward-worker:8081" }),
    ])
    const bot = document("discord-bot/steward.yml", [
      entry({ path: "guild-id", key: "guild-id", value: "8081" }),
    ])
    const hits = searchAcross(
      [
        { location: worker, document: worker },
        { location: bot, document: bot },
      ],
      "8081",
    )
    expect(hits.map((hit) => hit.location.path).sort()).toEqual([
      "discord-bot/steward.yml",
      "steward-worker/steward.yml",
    ])
  })

  it("skips a raw document - there are no entries to search", () => {
    const raw = { ...location({ path: "steward-worker/README.txt", name: "README.txt" }), raw: true as const, content: "8081" }
    const hits = searchAcross([{ location: raw, document: raw }], "8081")
    expect(hits).toEqual([])
  })

  it("skips a file whose document has not loaded yet", () => {
    const loc = location({ path: "steward-worker/steward.yml", name: "steward.yml" })
    const hits = searchAcross([{ location: loc, document: undefined }], "anything")
    expect(hits).toEqual([])
  })

  it("returns nothing for an empty query without looking at any document", () => {
    const worker = document("steward-worker/steward.yml", [
      entry({ path: "worker.base-url", key: "base-url", value: "http://steward-worker:8081" }),
    ])
    expect(searchAcross([{ location: worker, document: worker }], "")).toEqual([])
  })
})

describe("pending jump", () => {
  it("hands a jump to the one read that follows, then forgets it", () => {
    setPendingJump("steward-worker", { file: "steward-worker/steward.yml", path: "worker.base-url" })
    expect(takePendingJump("steward-worker")).toEqual({
      file: "steward-worker/steward.yml",
      path: "worker.base-url",
    })
    expect(takePendingJump("steward-worker")).toBeUndefined()
  })

  it("keeps jumps for different services apart", () => {
    setPendingJump("steward-worker", { file: "steward-worker/steward.yml", path: "a" })
    setPendingJump("discord-bot", { file: "discord-bot/steward.yml", path: "b" })
    expect(takePendingJump("discord-bot")?.path).toBe("b")
    expect(takePendingJump("steward-worker")?.path).toBe("a")
  })
})
