import { describe, expect, it } from "vitest"

import type { ConfigEntry, ConfigLocation, ConfigDocument } from "@/lib/api"
import {
  entryHaystack,
  rankValue,
  searchValue,
  matchesQuery,
  onPendingTextJump,
  searchAcross,
  setPendingJump,
  setPendingTextJump,
  takePendingJump,
  takePendingTextJump,
} from "@/lib/settings-search"

/** A secret's value must never be findable. */

function entry(over: Partial<ConfigEntry> & { path: string; key: string }): ConfigEntry {
  return {
    label: over.key,
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...over,
  }
}

function location(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return { service: "steward", label: "", live: true, readable: true, writable: true, ...over }
}

function document(path: string, entries: ConfigEntry[]): ConfigDocument {
  return { ...location({ path, name: path }), revision: "r1", entries }
}

describe("entryHaystack / matchesQuery", () => {
  it("matches on the label", () => {
    const e = entry({ path: "agent.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "base url")).toBe(true)
  })

  it("matches on the key path", () => {
    const e = entry({ path: "agent.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "agent.base-url")).toBe(true)
  })

  it("matches on the current value", () => {
    const e = entry({ path: "agent.base-url", key: "base-url", value: "http://steward:8081" })
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

  it("is case-insensitive", () => {
    const e = entry({ path: "agent.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "BASE URL")).toBe(true)
  })

  it("finds nothing for an empty query", () => {
    const e = entry({ path: "agent.base-url", key: "base-url", label: "Base url" })
    expect(matchesQuery(e, "   ")).toBe(false)
  })

  it("excludes a MAP heading - it renders nowhere past two levels, so a hit on it would jump nowhere", () => {
    const e = entry({ path: "agent.limits.retry", key: "retry", label: "Retry", kind: "MAP" })
    expect(matchesQuery(e, "retry")).toBe(false)
  })

  it("RED, then fixed: a secret's known value must never be findable by that value", () => {
    /** The entry carries the secret value anyway, so the guard holds without trusting the wire. */
    const token = "MTA1NzE4.super-secret-discord-token"
    const secretEntry = entry({
      path: "discord.bot-token",
      key: "bot-token",
      label: "Bot token",
      secret: true,
      environmentOverridden: false,
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
      environmentOverridden: false,
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
      environmentOverridden: false,
      items: ["https://discord.com/api/webhooks/leak-me-not"],
    })
    expect(matchesQuery(secretEntry, "leak-me-not")).toBe(false)
  })
})

describe("searchAcross", () => {
  it("pairs a hit with the file it lives in, across several files", () => {
    const steward = document("steward/steward", [
      entry({ path: "agent.base-url", key: "base-url", value: "http://steward:8081" }),
    ])
    const bot = document("discord-bot/steward.yml", [entry({ path: "guild-id", key: "guild-id", value: "8081" })])
    const hits = searchAcross(
      [
        { location: steward, document: steward },
        { location: bot, document: bot },
      ],
      "8081",
    )
    expect(hits.map((hit) => hit.location.path).toSorted()).toEqual(["discord-bot/steward.yml", "steward/steward"])
  })

  it("skips a file whose document has not loaded yet", () => {
    const loc = location({ path: "steward/steward", name: "steward" })
    const hits = searchAcross([{ location: loc, document: undefined }], "anything")
    expect(hits).toEqual([])
  })

  it("returns nothing for an empty query without looking at any document", () => {
    const steward = document("steward/steward", [
      entry({ path: "agent.base-url", key: "base-url", value: "http://steward:8081" }),
    ])
    expect(searchAcross([{ location: steward, document: steward }], "")).toEqual([])
  })
})

describe("pending jump", () => {
  it("hands a jump to the one read that follows, then forgets it", () => {
    setPendingJump("steward", { file: "steward/steward", path: "agent.base-url" })
    expect(takePendingJump("steward")).toEqual({
      file: "steward/steward",
      path: "agent.base-url",
    })
    expect(takePendingJump("steward")).toBeUndefined()
  })

  it("keeps jumps for different services apart", () => {
    setPendingJump("steward", { file: "steward/steward", path: "a" })
    setPendingJump("discord-bot", { file: "discord-bot/steward.yml", path: "b" })
    expect(takePendingJump("discord-bot")?.path).toBe("b")
    expect(takePendingJump("steward")?.path).toBe("a")
  })
})

describe("pending text jump", () => {
  it("hands a jump to the one read that follows, then forgets it", () => {
    setPendingTextJump({ id: "smp/grave.decay.announce", language: "en" })
    expect(takePendingTextJump()).toEqual({ id: "smp/grave.decay.announce", language: "en" })
    expect(takePendingTextJump()).toBeUndefined()
  })

  it("is apart from the config jump - neither is taken as the other", () => {
    setPendingJump("smp", { file: "smp/steward.yml", path: "grave.decay.enabled" })
    setPendingTextJump({ id: "smp/grave.decay.announce", language: "en" })
    expect(takePendingJump("smp")).toEqual({ file: "smp/steward.yml", path: "grave.decay.enabled" })
    expect(takePendingTextJump()).toEqual({ id: "smp/grave.decay.announce", language: "en" })
  })

  it("notifies a subscriber immediately - the same-page case, where nothing navigates", () => {
    const seen: string[] = []
    const unsubscribe = onPendingTextJump(() => seen.push("notified"))
    setPendingTextJump({ id: "smp/a", language: "en" })
    expect(seen).toEqual(["notified"])
    unsubscribe()
    setPendingTextJump({ id: "smp/b", language: "en" })
    expect(seen).toEqual(["notified"])
    takePendingTextJump() // Cleans up, so this jump does not leak into a later test.
  })
})

/** A row that must not match at all, and a row that must not come first. */
describe("rankValue - a name outranks a mention, and a subsequence is not a match", () => {
  const donor = searchValue(
    "Donor",
    "role-names.donor",
    "Granted on a donation and never revoked - safe to hand out manually in Discord.",
  )
  const donationCents = searchValue(
    "Donation cents",
    "donation-cents",
    "The extra amount that grants the donor role - also how a payment above the order total is recognised as a donation.",
  )
  const smpPage = searchValue("smp", "Services Log window and console for smp. container log console restart")

  it("scores a setting whose whole name was typed at the top of the scale", () => {
    expect(rankValue(donor, "Donor")).toBe(1)
  })

  it("refuses the service page that only contains those letters in order", () => {
    /**
     * "donor" is a subsequence of "window and console … restart", which cmdk's default filter matched.
     */
    expect(rankValue(smpPage, "donor")).toBe(0)
  })

  it("keeps a setting that merely mentions a donor, but below the one that is named it", () => {
    expect(rankValue(donationCents, "donor")).toBeGreaterThan(0)
    expect(rankValue(donationCents, "donor")).toBeLessThan(rankValue(donor, "donor"))
  })

  it("still finds a setting by a fragment of its explanation - the half that already worked", () => {
    expect(rankValue(donor, "Granted on")).toBeGreaterThan(0)
    expect(rankValue(donationCents, "Granted on")).toBe(0)
  })

  it("treats a hyphen and a space as the same spelling, which is the one thing cmdk got right", () => {
    expect(rankValue(searchValue("hunger-games", "Services"), "hunger games")).toBe(1)
    expect(rankValue(searchValue("Base url", "agent.base-url"), "base-url")).toBeGreaterThan(0)
  })

  it("ranks the start of a name above the middle of one", () => {
    const start = searchValue("Donor role", "")
    const middle = searchValue("Payment donor", "")
    expect(rankValue(start, "donor")).toBeGreaterThan(rankValue(middle, "donor"))
  })

  it("scores nothing for an empty query - an unopened search is not a match", () => {
    expect(rankValue(donor, "")).toBe(0)
    expect(rankValue(donor, "   ")).toBe(0)
  })
})
