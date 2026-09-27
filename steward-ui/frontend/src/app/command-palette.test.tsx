import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { CommandPalette } from "@/app/command-palette"
import { GERMAN_BACKUP_SYNONYM } from "@/app/run-search-terms"
import { useConfigDocuments, useConfigs, useMessageBundles, useMessageDocuments, useRuns } from "@/lib/queries"
import { queryResult } from "@/lib/query-fixtures"
import { takePendingJump, takePendingMessageJump } from "@/lib/settings-search"
import type {
  ConfigEntry,
  ConfigLocation,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
  ParsedConfigDocument,
  Run,
} from "@/lib/api"

/**
 * Ctrl+K, and who gets to keep it.
 *
 * The browser keeps its own Ctrl+K while somebody is typing, so the palette must not steal it from
 * a console line or a config field. The palette's own input is the deliberate exception: there the
 * shortcut is how you close it again, so the rule is *not while typing, unless the palette is
 * already open* - which is why this cannot be a test of `isEditable` alone.
 */

// The palette navigates on select; the tests below assert on this spy.
const navigateSpy = vi.fn<(options: Record<string, unknown>) => void>()
vi.mock("@tanstack/react-router", () => ({ useNavigate: () => navigateSpy }))

/**
 * The palette loads runs and settings to make them findable by more than their page title;
 * mocked rather than driven through a real QueryClientProvider + fetch stub. The message-bundle
 * pair mirrors the config pair one for one.
 */
vi.mock("@/lib/queries", () => ({
  useRuns: vi.fn<typeof useRuns>(),
  useConfigs: vi.fn<typeof useConfigs>(),
  useConfigDocuments: vi.fn<typeof useConfigDocuments>(),
  useMessageBundles: vi.fn<typeof useMessageBundles>(),
  useMessageDocuments: vi.fn<typeof useMessageDocuments>(),
}))

beforeEach(() => {
  // The default every test not about runs or settings gets: nothing loaded, unless a test asks for one of them specifically.
  vi.mocked(useRuns).mockReturnValue(queryResult([]))
  vi.mocked(useConfigs).mockReturnValue(queryResult([]))
  vi.mocked(useConfigDocuments).mockReturnValue([])
  vi.mocked(useMessageBundles).mockReturnValue(queryResult([]))
  vi.mocked(useMessageDocuments).mockReturnValue([])
})

afterEach(() => {
  cleanup()
  window.innerWidth = 1024
  navigateSpy.mockClear()
  vi.mocked(useRuns).mockReset()
  vi.mocked(useConfigs).mockReset()
  vi.mocked(useConfigDocuments).mockReset()
  vi.mocked(useMessageBundles).mockReset()
  vi.mocked(useMessageDocuments).mockReset()
})

/** The palette, identified by the one thing only the open dialog has. */
const searchInput = () => screen.queryByPlaceholderText("Search pages, runs, settings…")

function nonNull<T>(value: T | null, what: string): T {
  if (value === null) throw new Error(`expected ${what}`)
  return value
}

function ctrlK(target: Element | Document) {
  fireEvent.keyDown(target, { key: "k", ctrlKey: true })
}

/** A run, shaped like `/api/updates` answers it - only the fields this file's tests look at vary. */
function run(over: Partial<Run> = {}): Run {
  return {
    id: 91,
    scope: [],
    kind: "BACKUP",
    status: "FAILED",
    source: "SCHEDULE",
    requestedBy: "clock",
    actorDiscordId: "",
    actorLabel: "",
    system: true,
    requested: "2026-09-16T04:45:00Z",
    notBefore: "2026-09-16T04:45:00Z",
    started: "2026-09-16T04:45:00Z",
    finished: "2026-09-16T04:46:12Z",
    ...over,
  }
}

/** Opens the palette and types into it - the setup a search test starts from. */
async function search(query: string) {
  render(<CommandPalette />)
  ctrlK(document.body)
  const input = await waitFor(() => nonNull(searchInput(), "the search input"))
  fireEvent.change(input, { target: { value: query } })
  return input
}

function configLocation(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return { service: "steward-worker", readable: true, writable: true, ...over }
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

/** Wires `useConfigs`/`useConfigDocuments` for one file, the way the palette actually pairs them: by index, in the order `locations` came back in. */
function oneFile(loc: ConfigLocation, entries: ConfigEntry[]) {
  vi.mocked(useConfigs).mockReturnValue(queryResult([loc]))
  const document: ParsedConfigDocument = { ...loc, revision: "r1", header: [], entries }
  vi.mocked(useConfigDocuments).mockReturnValue([queryResult(document)])
}

function bundleLocation(over: Partial<MessageBundleLocation> & { path: string }): MessageBundleLocation {
  return { service: "smp", module: "smp", writable: true, ...over }
}

function messageEntry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return { inBundle: true, args: [], section: [], ...over }
}

/** Wires `useMessageBundles`/`useMessageDocuments` for one bundle, the same pairing-by-index `oneFile` does for a config file. */
function oneBundle(loc: MessageBundleLocation, entries: MessageEntry[]) {
  vi.mocked(useMessageBundles).mockReturnValue(queryResult([loc]))
  const document: MessageBundle = { ...loc, entries }
  vi.mocked(useMessageDocuments).mockReturnValue([queryResult(document)])
}

function accessFileScalar(path: string, key: string, label: string, explanation: string, value: string): ConfigEntry {
  return {
    path,
    key,
    label,
    comments: [],
    explanation,
    noExplanationNeeded: false,
    filled: true,
    value,
    items: [],
    kind: "SCALAR",
    type: "STRING",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
  }
}

/** The real `discord-bot/access.yml` shape, read off a running host: no comments any more (jcore rewrote it without them), so a `roles.donor` entry is found by its label, its path and its `@Explain` line and by nothing else. */
function accessFile() {
  const loc: ConfigLocation = {
    service: "discord-bot",
    name: "access.yml",
    path: "discord-bot/access.yml",
    readable: true,
    writable: true,
  }
  const entries: ConfigEntry[] = [
    accessFileScalar(
      "donation-cents",
      "donation-cents",
      "Donation cents",
      "The extra amount that grants the donor role - also how a payment above the order total is recognised as a donation.",
      "500",
    ),
    accessFileScalar(
      "roles.access",
      "access",
      "Access",
      "Bot-managed: granting it by hand only holds until the next reconcile. Use /grant-access instead.",
      "1544515346940301384",
    ),
    accessFileScalar(
      "roles.donor",
      "donor",
      "Donor",
      "Granted on a donation and never revoked - safe to hand out manually in Discord.",
      "1544515504889139301",
    ),
  ]
  vi.mocked(useConfigs).mockReturnValue(queryResult([loc]))
  vi.mocked(useConfigDocuments).mockReturnValue([queryResult({ ...loc, revision: "r1", header: [], entries })])
}

/** Every row the palette is currently showing, top to bottom, by its visible text. */
function paletteItems(): string[] {
  return Array.from(document.querySelectorAll("[cmdk-item]")).map((row) => (row.textContent ?? "").trim())
}

/** The same setting, by the same name, in two services - which is the real case. */
function twoServicesWithTheSameSetting() {
  const worker = configLocation({ path: "steward-worker/steward.yml", name: "steward.yml" })
  const bot = configLocation({ path: "discord-bot/steward.yml", name: "steward.yml", service: "discord-bot" })
  const same = configEntry({ path: "worker.base-url", key: "base-url", label: "Base url" })
  vi.mocked(useConfigs).mockReturnValue(queryResult([worker, bot]))
  vi.mocked(useConfigDocuments).mockReturnValue([
    queryResult({ ...worker, revision: "r1", header: [], entries: [same] }),
    queryResult({ ...bot, revision: "r1", header: [], entries: [{ ...same }] }),
  ])
}

describe("CommandPalette - Ctrl+K", () => {
  it("opens on Ctrl+K when nobody is typing", async () => {
    render(<CommandPalette />)
    expect(searchInput()).toBeNull()
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())
  })

  it("opens on Cmd+K as well, so the label the interface prints is a courtesy", async () => {
    render(<CommandPalette />)
    fireEvent.keyDown(document.body, { key: "k", metaKey: true })
    await waitFor(() => expect(searchInput()).not.toBeNull())
  })

  it("leaves a plain k alone", () => {
    render(<CommandPalette />)
    fireEvent.keyDown(document.body, { key: "k" })
    expect(searchInput()).toBeNull()
  })

  it.each(["INPUT", "TEXTAREA", "SELECT"])(
    "does not open while somebody is typing into a %s - the console line keeps its Ctrl+K",
    (tag) => {
      render(<CommandPalette />)
      const field = document.createElement(tag.toLowerCase())
      document.body.append(field)
      ctrlK(field)
      expect(searchInput()).toBeNull()
      field.remove()
    },
  )

  it("does not open from a contenteditable either", () => {
    render(<CommandPalette />)
    const field = document.createElement("div")
    field.contentEditable = "true"
    // jsdom does not derive isContentEditable from the attribute, so it is set directly.
    Object.defineProperty(field, "isContentEditable", { value: true })
    document.body.append(field)
    ctrlK(field)
    expect(searchInput()).toBeNull()
    field.remove()
  })

  it("closes again on Ctrl+K in its own input, which is the one typing that counts", async () => {
    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())
    const input = nonNull(searchInput(), "the search input")
    ctrlK(input)
    await waitFor(() => expect(searchInput()).toBeNull())
  })
})

/**
 * A run is findable the same way a page is: by its number, its kind, its outcome, and a synonym
 * nobody would find in an English page title - German included (imported from
 * `run-search-terms.ts` rather than spelled out here, which is what keeps this file out of
 * `language.test.ts`'s exemption list - see that file's `EXEMPT` set). Before this, only the four
 * groups of pages in `navigation.ts` were searchable, none of which is called "report", so a run
 * was never in the palette to begin with.
 */
describe("CommandPalette - finding a run", () => {
  it("still finds a page by its title", async () => {
    // Restore is a dialog on Backups now, and the word still finds the page that holds it.
    await search("restore")
    expect(screen.queryByText("Backups")).not.toBeNull()
  })

  it('finds the failed backup run on "report"', async () => {
    vi.mocked(useRuns).mockReturnValue(queryResult([run()]))
    await search("report")
    expect(screen.queryByText(/Run #91/)).not.toBeNull()
  })

  it("takes German with it: the German synonym on the backup entry finds the same run", async () => {
    vi.mocked(useRuns).mockReturnValue(queryResult([run()]))
    await search(GERMAN_BACKUP_SYNONYM)
    expect(screen.queryByText(/Run #91/)).not.toBeNull()
  })

  it("makes a run findable by its outcome, not only by its kind", async () => {
    vi.mocked(useRuns).mockReturnValue(queryResult([run({ id: 12, kind: "UPDATE", status: "FAILED" })]))
    await search("failed")
    expect(screen.queryByText(/Run #12/)).not.toBeNull()
  })

  it("makes a run findable by its kind, e.g. a restart", async () => {
    vi.mocked(useRuns).mockReturnValue(queryResult([run({ id: 7, kind: "RESTART", status: "DONE" })]))
    await search("restart")
    expect(screen.queryByText(/Run #7/)).not.toBeNull()
  })

  it('selecting a run entry navigates to that run, not to "latest"', async () => {
    vi.mocked(useRuns).mockReturnValue(queryResult([run({ id: 91 })]))
    await search("report")
    const item = await screen.findByText(/Run #91/)
    fireEvent.click(item)
    // The fixture is a BACKUP run, whose page is under Backups.
    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/operations/backups/$id",
      params: { id: "91" },
    })
  })
})

/**
 * A search across every service's settings, with the service named in the hit, reachable from
 * anywhere the same way a page or a run already is.
 */
describe("CommandPalette - finding a setting", () => {
  it("finds a setting by its label and names the service it belongs to", async () => {
    const loc = configLocation({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [configEntry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    await search("base url")

    expect(screen.queryByText("Base url")).not.toBeNull()
    expect(screen.queryByText(/steward-worker/)).not.toBeNull()
  })

  it("finds a setting by its current value", async () => {
    const loc = configLocation({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [
      configEntry({ path: "worker.base-url", key: "base-url", label: "Base url", value: "http://steward-worker:8081" }),
    ])

    await search("8081")

    expect(screen.queryByText("Base url")).not.toBeNull()
  })

  it("never finds a secret by its value, even when it is the only thing typed", async () => {
    const loc = configLocation({ path: "discord-bot/steward.yml", name: "steward.yml", service: "discord-bot" })
    const token = "super-secret-discord-token"
    oneFile(loc, [
      /**
       * As if a future bug sent a value for a secret anyway - the client's own guard has to hold
       * regardless of what the wire happened to include.
       */
      configEntry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: token }),
    ])

    await search(token)

    expect(screen.queryByText("Bot token")).toBeNull()
  })

  it("still finds that same secret entry by its label - only the value is excluded", async () => {
    const loc = configLocation({ path: "discord-bot/steward.yml", name: "steward.yml", service: "discord-bot" })
    oneFile(loc, [
      configEntry({
        path: "discord.bot-token",
        key: "bot-token",
        label: "Bot token",
        secret: true,
        value: "irrelevant",
      }),
    ])

    await search("bot token")

    expect(screen.queryByText("Bot token")).not.toBeNull()
  })

  it("shows nothing before anything is typed - not hundreds of settings on open", async () => {
    const loc = configLocation({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [configEntry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.queryByText("Base url")).toBeNull()
  })

  it("keeps the trailing grey column off a phone entirely", async () => {
    /**
     * The right-aligned grey text carries the path, and on a narrow row the path is the thing
     * that shortens the name in order to be cut off itself - two truncated strings where one
     * whole one would have fitted, so it stays hidden below the mobile breakpoint instead.
     *
     * A class assertion and not a visual one: jsdom applies no media query, so "gone below 640px"
     * can only be stated as the pair of utilities that says it. 640px is `useIsMobile`'s own
     * breakpoint, which is what every other narrow/wide decision in this app switches on.
     */
    const loc = configLocation({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [configEntry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    await search("base url")
    await screen.findByText("Base url")

    const trailing = document.querySelectorAll('[data-slot="command-shortcut"]')
    expect(trailing.length).toBeGreaterThan(0)
    for (const column of trailing) {
      expect(column.className).toContain("hidden")
      expect(column.className).toContain("sm:block")
    }
  })

  it("selecting a hit navigates to that service's page and hands it a jump", async () => {
    const loc = configLocation({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [configEntry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    await search("base url")
    fireEvent.click(await screen.findByText("Base url"))

    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/services/$name",
      params: { name: "steward-worker" },
      search: { tab: "settings", file: "steward-worker/steward.yml" },
    })
    expect(takePendingJump("steward-worker")).toEqual({
      file: "steward-worker/steward.yml",
      path: "worker.base-url",
    })
  })
})

/**
 * Message bundles are a second supplier for the same global search: a text that lives only in a
 * bundle, and in no config file, is still found.
 */
describe("CommandPalette - finding a message bundle key", () => {
  it("finds a bundle key that no config file mentions", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })])

    await search("decayed")

    /**
     * The row is named the way the Settings tab names the text - its last key segment made
     * readable, when no spec names it - and the matched text rides along on the right.
     */
    expect(screen.queryByText("Announce")).not.toBeNull()
    expect(screen.queryByText(/Your grave has decayed\./)).not.toBeNull()
  })

  it("finds a key by its German translation, not only its English default", async () => {
    /**
     * A synthetic marker, not real German prose - `language.test.ts` scans every source file for
     * German and a fixture is not exempt from that, the same reason `messages.test.tsx` spells its
     * own German fixtures as "packaged-de-text" rather than an actual sentence.
     */
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [
      messageEntry({
        key: "grave.decay.announce",
        english: "Your grave has decayed.",
        german: "packaged-de-marker",
      }),
    ])

    await search("de-marker")

    expect(screen.queryByText(/packaged-de-marker/)).not.toBeNull()
  })

  it("finds a key by the key itself", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })])

    await search("grave.decay")

    // Matched by the key, which the row itself never shows.
    expect(screen.queryByText("Announce")).not.toBeNull()
    expect(screen.queryByText(/grave\.decay/)).toBeNull()
  })

  it("shows nothing before anything is typed, same as a config hit", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })])

    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.queryByText("grave.decay.announce")).toBeNull()
  })

  it("selecting a bundle hit navigates to the service page and hands the messages tool a jump, not the configuration form", async () => {
    const loc = bundleLocation({ path: "smp/smp", service: "smp" })
    oneBundle(loc, [messageEntry({ key: "grave.decay.announce", english: "Your grave has decayed." })])

    await search("decayed")
    fireEvent.click(await screen.findByText("Announce"))

    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/services/$name",
      params: { name: "smp" },
      search: { tab: "settings", file: "bundle:smp/smp" },
    })
    expect(takePendingMessageJump("smp")).toEqual({
      path: "smp/smp",
      language: "en",
      key: "grave.decay.announce",
    })
    // And never the config map - a bundle hit must not be mistaken for a config one downstream.
    expect(takePendingJump("smp")).toBeUndefined()
  })

  it("finds a config hit and a bundle hit together, in one list", async () => {
    const configLoc: ConfigLocation = {
      service: "smp",
      name: "steward.yml",
      path: "smp/steward.yml",
      readable: true,
      writable: true,
    }
    const gravEntry: ConfigEntry = {
      path: "grave.decay.enabled",
      key: "enabled",
      label: "Grave decay enabled",
      comments: [],
      explanation: "",
      noExplanationNeeded: false,
      filled: true,
      kind: "SCALAR",
      type: "BOOLEAN",
      line: 1,
      editable: true,
      secret: false,
      inSchema: true,
    }
    vi.mocked(useConfigs).mockReturnValue(queryResult([configLoc]))
    vi.mocked(useConfigDocuments).mockReturnValue([
      queryResult({ ...configLoc, revision: "r1", header: [], entries: [gravEntry] }),
    ])
    oneBundle(bundleLocation({ path: "smp/smp", service: "smp" }), [
      messageEntry({ key: "grave.decay.announce", english: "Grave decay announcement" }),
    ])

    await search("grave decay")

    expect(screen.queryByText("Grave decay enabled")).not.toBeNull()
    expect(screen.queryByText("Announce")).not.toBeNull()
  })
})

/**
 * `cmdk` gives the input `role="combobox"`, and that overrides the native textbox role. The
 * browser's placeholder-as-name fallback (HTML-AAM) applies to the native role only, so the
 * placeholder stops counting once the role is set - a screen reader would announce "combobox" and
 * nothing about what it searches, unless the field also carries an accessible name.
 */
describe("CommandPalette - the input says what it is", () => {
  it("is findable by role and name, not only by its placeholder", async () => {
    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.getByRole("combobox", { name: "Search pages, runs, settings" })).toBe(searchInput())
  })
})

/**
 * A setting named "Donor" outranks every service page that only fuzzily contains those letters.
 * `discord-bot/access.yml` carries no comments (jcore rewrites it without them), so a
 * `roles.donor` entry is found by its label, its path and its `@Explain` line and by nothing else.
 *
 * The assertion is an order, not a presence. `items()` reads the palette's own list in DOM order,
 * which is what cmdk sorts and therefore what a person sees.
 */
describe("CommandPalette - what an exact name outranks", () => {
  it("puts the setting named Donor above the service pages that only fuzzily contain those letters", async () => {
    accessFile()

    await search("Donor")

    const rows = paletteItems()
    const donor = rows.findIndex((row) => row.startsWith("Donor"))
    expect(donor).toBeGreaterThanOrEqual(0)
    const firstService = rows.findIndex((row) => /^(smp|limbo|postgres|caddy)/.test(row))
    expect(donor).toBeLessThan(firstService === -1 ? rows.length : firstService)
    expect(donor).toBe(0)
  })
})

/**
 * `cmdk` identifies a row by its `value`, and the palette must not hand it the *haystack* as that
 * value - two rows whose searchable text happens to be identical would otherwise be, to cmdk, one
 * row, so pointing at either lights both. A setting called `base-url` exists in several services,
 * and `entryHaystack` is built from the entry alone, never from the file it sits in.
 */
describe("CommandPalette - one row lights up, not every row that reads alike", () => {
  it("draws both hits, because they are two different settings in two different services", async () => {
    twoServicesWithTheSameSetting()

    await search("base url")

    const rows = Array.from(document.querySelectorAll("[cmdk-item]"))
    expect(rows.length).toBe(2)
  })

  it("marks exactly one of them as selected", async () => {
    twoServicesWithTheSameSetting()

    await search("base url")

    const selected = document.querySelectorAll('[cmdk-item][aria-selected="true"]')
    expect(selected.length).toBe(1)
  })
})

/**
 * The palette is a bottom sheet on a phone like everything else that opens. `vaul` lifts a sheet
 * whose input has focus above the on-screen keyboard, so there is no reason left to keep it a
 * centred dialog there. What jsdom can say is which shell was mounted; whether the lift looks
 * right on a real phone needs a real device.
 */
describe("CommandPalette - the shell is a sheet on a phone", () => {
  it("is a centred dialog on a desktop", async () => {
    window.innerWidth = 1024
    render(<CommandPalette />)
    ctrlK(document.body)

    const input = await waitFor(() => nonNull(searchInput(), "the search input"))
    expect(input.closest("[data-slot='dialog-content']")).not.toBeNull()
    expect(input.closest("[data-slot='drawer-content']")).toBeNull()
  })

  it("is a bottom sheet on a phone", async () => {
    window.innerWidth = 390
    render(<CommandPalette />)
    ctrlK(document.body)

    const input = await waitFor(() => nonNull(searchInput(), "the search input"))
    expect(input.closest("[data-slot='drawer-content']")).not.toBeNull()
    expect(input.closest("[data-slot='dialog-content']")).toBeNull()
  })

  it("takes the typing at once on a phone, and spans the sheet's full width", async () => {
    window.innerWidth = 390
    render(<CommandPalette />)
    ctrlK(document.body)

    const input = await waitFor(() => nonNull(searchInput(), "the search input"))
    await waitFor(() => expect(document.activeElement).toBe(input))
    // The dialog's width and offset are the desktop half; on a sheet they shrank it and pushed it aside.
    const sheet = nonNull(input.closest("[data-slot='drawer-content']"), "the drawer content")
    const bare = sheet.className.split(/\s+/).filter((name) => !name.includes(":"))
    expect(
      bare.some((name) => name.startsWith("w-[") || name.startsWith("top-[") || name.startsWith("translate-")),
    ).toBe(false)
  })
})
