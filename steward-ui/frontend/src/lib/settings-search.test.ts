import { describe, expect, it } from "vitest"

import type {
  ConfigEntry,
  ConfigLocation,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
  ParsedConfigDocument,
} from "@/lib/api"
import {
  entryHaystack,
  rankValue,
  searchValue,
  matchesMessageQuery,
  matchesQuery,
  messageEntryHaystack,
  onPendingMessageJump,
  searchAcross,
  searchMessagesAcross,
  searchSettingsAndMessages,
  setPendingJump,
  setPendingMessageJump,
  takePendingJump,
  takePendingMessageJump,
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

// --- steward/87: the message bundles are a second supplier, not a second search --------------

function messageEntry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return { inBundle: true, ...over }
}

function bundleLocation(
  over: Partial<MessageBundleLocation> & { path: string },
): MessageBundleLocation {
  return { service: "smp", module: "smp", writable: true, ...over }
}

function bundle(loc: MessageBundleLocation, entries: MessageEntry[]): MessageBundle {
  return { ...loc, entries }
}

describe("messageEntryHaystack / matchesMessageQuery", () => {
  it("matches the English default", () => {
    const e = messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })
    expect(matchesMessageQuery(e, "en", "decayed")).toBe(true)
  })

  it("matches the German translation, and only for the German language", () => {
    // A synthetic marker, not real German prose - `language.test.ts` scans every source file for
    // German and a fixture is not exempt from that, the same reason `messages.test.tsx` (steward/48)
    // spells its own German fixtures as "packaged-de-text" rather than an actual sentence.
    const e = messageEntry({
      key: "grave.decay.announce",
      english: "Your grave has decayed.",
      german: "packaged-de-marker",
    })
    expect(matchesMessageQuery(e, "de", "de-marker")).toBe(true)
    expect(matchesMessageQuery(e, "en", "de-marker")).toBe(false)
  })

  it("matches an operator's override, not only the packaged text", () => {
    const e = messageEntry({
      key: "grave.decay.announce",
      english: "Your grave has decayed.",
      overrideEnglish: "The grave is being cleared.",
    })
    expect(matchesMessageQuery(e, "en", "cleared")).toBe(true)
  })

  it("matches the key, in both languages - the key has no language of its own", () => {
    const e = messageEntry({ key: "grave.decay.announce", english: "x", german: "y" })
    expect(matchesMessageQuery(e, "en", "grave.decay")).toBe(true)
    expect(matchesMessageQuery(e, "de", "grave.decay")).toBe(true)
  })

  it("is case-insensitive", () => {
    const e = messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })
    expect(matchesMessageQuery(e, "en", "DECAYED")).toBe(true)
  })

  it("finds nothing for an empty query", () => {
    const e = messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })
    expect(matchesMessageQuery(e, "en", "   ")).toBe(false)
  })

  it("does not leak the other language's text into this one's haystack", () => {
    const e = messageEntry({ key: "grave.decay.announce", english: "wipe", german: "de-only-marker" })
    expect(messageEntryHaystack(e, "en")).not.toContain("de-only-marker")
    expect(messageEntryHaystack(e, "de")).not.toContain("wipe")
  })
})

describe("searchMessagesAcross", () => {
  it("finds a key by its English default and its German translation as two separate hits", () => {
    const loc = bundleLocation({ path: "smp/smp" })
    const doc = bundle(loc, [
      messageEntry({
        key: "grave.decay.announce",
        english: "Your grave has decayed.",
        german: "packaged-de-marker",
      }),
    ])
    // A query that matches the key matches it in both languages - one hit per language, since
    // each is a different destination (a different tab) once it is found.
    const hits = searchMessagesAcross([{ location: loc, bundle: doc }], "grave.decay")
    expect(hits.map((hit) => hit.language).sort()).toEqual(["de", "en"])
  })

  it("skips a bundle whose document has not loaded yet", () => {
    const loc = bundleLocation({ path: "smp/smp" })
    expect(searchMessagesAcross([{ location: loc, bundle: undefined }], "anything")).toEqual([])
  })

  it("returns nothing for an empty query without looking at any bundle", () => {
    const loc = bundleLocation({ path: "smp/smp" })
    const doc = bundle(loc, [messageEntry({ key: "a", english: "b" })])
    expect(searchMessagesAcross([{ location: loc, bundle: doc }], "")).toEqual([])
  })

  it("tags every hit with kind: \"message\", so a caller can tell it apart from a config hit", () => {
    const loc = bundleLocation({ path: "smp/smp" })
    const doc = bundle(loc, [messageEntry({ key: "a", english: "wipe" })])
    const [hit] = searchMessagesAcross([{ location: loc, bundle: doc }], "wipe")
    expect(hit.kind).toBe("message")
  })
})

describe("searchSettingsAndMessages - one list, from two suppliers", () => {
  function configLocation(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
    return { service: "smp", readable: true, writable: true, ...over }
  }

  function configEntry(over: Partial<ConfigEntry> & { path: string; key: string }): ConfigEntry {
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

  function configDocument(loc: ConfigLocation, entries: ConfigEntry[]): ParsedConfigDocument {
    return { ...loc, revision: "r1", header: [], entries }
  }

  /**
   * steward/87's own words, made literal: "a text that lives in only one bundle is not found
   * before, and is found after". This is that sentence, with a fixture proving the "before" half
   * too - the config file has entries, none of which mention the text, so a config-only search
   * (`searchAcross` alone) would answer nothing for this query.
   */
  it("finds a text that lives only in a bundle - not in any config file of the same service", () => {
    const loc = configLocation({ path: "smp/steward.yml", name: "steward.yml" })
    const configDoc = configDocument(loc, [
      configEntry({ path: "grave.decay.enabled", key: "enabled", label: "Grave decay enabled" }),
    ])
    const bundleLoc = bundleLocation({ path: "smp/smp" })
    const bundleDoc = bundle(bundleLoc, [
      messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." }),
    ])

    // The "before" half: config search alone finds nothing for this text.
    expect(searchAcross([{ location: loc, document: configDoc }], "decayed")).toEqual([])

    // The "after" half: both suppliers together find it.
    const hits = searchSettingsAndMessages(
      [{ location: loc, document: configDoc }],
      [{ location: bundleLoc, bundle: bundleDoc }],
      "decayed",
    )
    expect(hits).toHaveLength(1)
    expect(hits[0]).toMatchObject({ kind: "message", entry: { key: "grave.decay.announce" } })
  })

  it("still finds a config hit when the query matches only a config file", () => {
    const loc = configLocation({ path: "smp/steward.yml", name: "steward.yml" })
    const configDoc = configDocument(loc, [
      configEntry({ path: "grave.decay.enabled", key: "enabled", label: "Grave decay enabled" }),
    ])
    const hits = searchSettingsAndMessages(
      [{ location: loc, document: configDoc }],
      [],
      "grave decay enabled",
    )
    expect(hits).toHaveLength(1)
    expect(hits[0].kind).toBe("config")
  })

  it("finds both a config hit and a message hit for one query, in one list", () => {
    const loc = configLocation({ path: "smp/steward.yml", name: "steward.yml" })
    const configDoc = configDocument(loc, [
      configEntry({ path: "grave.decay.enabled", key: "enabled", label: "Grave decay enabled" }),
    ])
    const bundleLoc = bundleLocation({ path: "smp/smp" })
    const bundleDoc = bundle(bundleLoc, [
      messageEntry({ key: "grave.decay.announce", english: "Grave decay announcement" }),
    ])

    const hits = searchSettingsAndMessages(
      [{ location: loc, document: configDoc }],
      [{ location: bundleLoc, bundle: bundleDoc }],
      "grave decay",
    )
    expect(hits.map((hit) => hit.kind).sort()).toEqual(["config", "message"])
  })
})

describe("pending message jump", () => {
  it("hands a jump to the one read that follows, then forgets it", () => {
    setPendingMessageJump("smp", { path: "smp/smp", language: "en", key: "grave.decay.announce" })
    expect(takePendingMessageJump("smp")).toEqual({
      path: "smp/smp",
      language: "en",
      key: "grave.decay.announce",
    })
    expect(takePendingMessageJump("smp")).toBeUndefined()
  })

  it("keeps jumps for different services apart", () => {
    setPendingMessageJump("smp", { path: "smp/smp", language: "en", key: "a" })
    setPendingMessageJump("discord-bot", { path: "discord-bot", language: "de", key: "b" })
    expect(takePendingMessageJump("discord-bot")?.key).toBe("b")
    expect(takePendingMessageJump("smp")?.key).toBe("a")
  })

  it("is a separate map from the config jump - the same service name in both never collides", () => {
    setPendingJump("smp", { file: "smp/steward.yml", path: "grave.decay.enabled" })
    setPendingMessageJump("smp", { path: "smp/smp", language: "en", key: "grave.decay.announce" })
    expect(takePendingJump("smp")).toEqual({ file: "smp/steward.yml", path: "grave.decay.enabled" })
    expect(takePendingMessageJump("smp")).toEqual({
      path: "smp/smp",
      language: "en",
      key: "grave.decay.announce",
    })
  })

  it("notifies a subscriber immediately - the same-page case, where nothing navigates", () => {
    const seen: string[] = []
    const unsubscribe = onPendingMessageJump(() => seen.push("notified"))
    setPendingMessageJump("smp", { path: "smp/smp", language: "en", key: "a" })
    expect(seen).toEqual(["notified"])
    unsubscribe()
    setPendingMessageJump("smp", { path: "smp/smp", language: "en", key: "b" })
    expect(seen).toEqual(["notified"]) // unsubscribed: no second notification
    takePendingMessageJump("smp") // clean up, so this jump does not leak into a later test
  })
})

/**
 * steward/105: what "Donor" is allowed to find, and in which order.
 *
 * The values below are the real ones, taken from what `command-palette.tsx` hands cmdk and from
 * this host's own `discord-bot/access.yml` (2026-09-18). Each case is one of the two faults the
 * ticket is about: a row that must not match at all, and a row that must not come first.
 */
describe("rankValue - a name outranks a mention, and a subsequence is not a match (steward/105)", () => {
  const donor = searchValue(
    "Donor",
    "roles.donor",
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
    // d-o-n-o-r is hidden in "win(d)ow and c(o)ns(o)le … (r)estart", which is exactly what cmdk's
    // default filter matched and why every service was offered.
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
    expect(rankValue(searchValue("steward-ui", "Services"), "steward ui")).toBe(1)
    expect(rankValue(searchValue("Base url", "worker.base-url"), "base-url")).toBeGreaterThan(0)
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
