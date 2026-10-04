import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { CommandPalette } from "@/app/command-palette"
import { GERMAN_BACKUP_SYNONYM } from "@/app/run-search-terms"
import {
  useConfigDocuments,
  useConfigs,
  useMessageBundles,
  useMessageDocuments,
  useRuns,
  useTopology,
} from "@/lib/queries"
import { NETWORK_MAP, queryResult } from "@/lib/query-fixtures"
import { takePendingJump, takePendingMessageJump } from "@/lib/settings-search"
import type {
  ConfigEntry,
  ConfigLocation,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
  ConfigDocument,
  Run,
} from "@/lib/api"

// The palette navigates on select; the tests below assert on this spy.
const navigateSpy = vi.fn<(options: Record<string, unknown>) => void>()
vi.mock("@tanstack/react-router", () => ({ useNavigate: () => navigateSpy }))

/** Runs, configs and bundles are mocked rather than fetched through a real client. */
vi.mock("@/lib/queries", () => ({
  useRuns: vi.fn<typeof useRuns>(),
  useConfigs: vi.fn<typeof useConfigs>(),
  useConfigDocuments: vi.fn<typeof useConfigDocuments>(),
  useMessageBundles: vi.fn<typeof useMessageBundles>(),
  useMessageDocuments: vi.fn<typeof useMessageDocuments>(),
  useTopology: vi.fn<typeof useTopology>(),
}))

beforeEach(() => {
  // Nothing loaded by default; a test about runs or settings asks for its own.
  vi.mocked(useRuns).mockReturnValue(queryResult([]))
  vi.mocked(useConfigs).mockReturnValue(queryResult([]))
  vi.mocked(useConfigDocuments).mockReturnValue([])
  vi.mocked(useMessageBundles).mockReturnValue(queryResult([]))
  vi.mocked(useMessageDocuments).mockReturnValue([])
  vi.mocked(useTopology).mockReturnValue(queryResult(NETWORK_MAP))
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
  vi.mocked(useTopology).mockReset()
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

/** A run shaped like `/api/updates` answers it; only the fields these tests look at vary. */
function run(over: Partial<Run> = {}): Run {
  return {
    id: 91,
    scope: [],
    kind: "BACKUP",
    status: "FAILED",
    actorKind: "HOST",
    actorId: "",
    requested: "2026-09-16T04:45:00Z",
    scheduledFor: "2026-09-16T04:45:00Z",
    countdownEnd: "2026-09-16T04:45:00Z",
    moving: [],
    started: "2026-09-16T04:45:00Z",
    finished: "2026-09-16T04:46:12Z",
    ...over,
  }
}

/** Opens the palette and types into it. */
async function search(query: string) {
  render(<CommandPalette />)
  ctrlK(document.body)
  const input = await waitFor(() => nonNull(searchInput(), "the search input"))
  fireEvent.change(input, { target: { value: query } })
  return input
}

function configLocation(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return { service: "steward", label: "", live: true, readable: true, writable: true, ...over }
}

function configEntry(over: Partial<ConfigEntry> & { path: string; key: string }): ConfigEntry {
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

/** Wires `useConfigs` and `useConfigDocuments` for one file, paired by index as the palette pairs them. */
function oneFile(loc: ConfigLocation, entries: ConfigEntry[]) {
  vi.mocked(useConfigs).mockReturnValue(queryResult([loc]))
  const document: ConfigDocument = { ...loc, revision: "r1", restartRequired: false, entries }
  vi.mocked(useConfigDocuments).mockReturnValue([queryResult(document)])
}

function bundleLocation(over: Partial<MessageBundleLocation> & { path: string }): MessageBundleLocation {
  return { service: "smp", module: "smp", writable: true, ...over }
}

function messageEntry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return { bundle: "smp", inBundle: true, texts: {}, overrides: {}, args: [], section: [], ...over }
}

/** Wires `useMessageBundles` and `useMessageDocuments` for one bundle, paired by index like `oneFile`. */
function oneBundle(loc: MessageBundleLocation, entries: MessageEntry[]) {
  vi.mocked(useMessageBundles).mockReturnValue(queryResult([loc]))
  const document: MessageBundle = { ...loc, entries, previews: {} }
  vi.mocked(useMessageDocuments).mockReturnValue([queryResult(document)])
}

function accessFileScalar(path: string, key: string, label: string, explanation: string, value: string): ConfigEntry {
  return {
    path,
    key,
    label,
    explanation,
    noExplanationNeeded: false,
    filled: true,
    value,
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
  }
}

/** The real `discord-bot/access` shape: no comments, so `roles.donor` is found by label, path and `@Explain`. */
function accessFile() {
  const loc: ConfigLocation = {
    service: "discord-bot",
    name: "access",
    path: "discord-bot/access",
    label: "",
    live: true,
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
  vi.mocked(useConfigDocuments).mockReturnValue([
    queryResult({ ...loc, revision: "r1", restartRequired: false, entries }),
  ])
}

/** Every row the palette is currently showing, top to bottom, by its visible text. */
function paletteItems(): string[] {
  return Array.from(document.querySelectorAll("[cmdk-item]")).map((row) => (row.textContent ?? "").trim())
}

/** The same setting, by the same name, in two services, which is the real case. */
function twoServicesWithTheSameSetting() {
  const steward = configLocation({ path: "steward/steward", name: "steward" })
  const bot = configLocation({ path: "discord-bot/steward.yml", name: "steward", service: "discord-bot" })
  const same = configEntry({ path: "agent.base-url", key: "base-url", label: "Base url" })
  vi.mocked(useConfigs).mockReturnValue(queryResult([steward, bot]))
  vi.mocked(useConfigDocuments).mockReturnValue([
    queryResult({ ...steward, revision: "r1", restartRequired: false, entries: [same] }),
    queryResult({ ...bot, revision: "r1", restartRequired: false, entries: [{ ...same }] }),
  ])
}

/** Ctrl+K stays the browser's while typing, except in the palette's own input, where it closes. */
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

/** A run is found by its number, kind, outcome and search synonyms, the German one imported, not spelled. */
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

/** Every service's settings are searchable, with the service named in the hit. */
describe("CommandPalette - finding a setting", () => {
  it("finds a setting by its label and names the service it belongs to", async () => {
    const loc = configLocation({ path: "steward/steward", name: "steward" })
    oneFile(loc, [configEntry({ path: "agent.base-url", key: "base-url", label: "Base url" })])

    await search("base url")

    expect(screen.queryByText("Base url")).not.toBeNull()
    expect(screen.queryByText(/steward/)).not.toBeNull()
  })

  it("finds a setting by its current value", async () => {
    const loc = configLocation({ path: "steward/steward", name: "steward" })
    oneFile(loc, [
      configEntry({ path: "agent.base-url", key: "base-url", label: "Base url", value: "http://steward:8081" }),
    ])

    await search("8081")

    expect(screen.queryByText("Base url")).not.toBeNull()
  })

  it("never finds a secret by its value, even when it is the only thing typed", async () => {
    const loc = configLocation({ path: "discord-bot/steward.yml", name: "steward", service: "discord-bot" })
    const token = "super-secret-discord-token"
    oneFile(loc, [
      /** As if a bug sent a secret's value anyway: the client's own guard has to hold. */
      configEntry({
        path: "discord.bot-token",
        key: "bot-token",
        label: "Bot token",
        secret: true,
        environmentOverridden: false,
        value: token,
      }),
    ])

    await search(token)

    expect(screen.queryByText("Bot token")).toBeNull()
  })

  it("still finds that same secret entry by its label - only the value is excluded", async () => {
    const loc = configLocation({ path: "discord-bot/steward.yml", name: "steward", service: "discord-bot" })
    oneFile(loc, [
      configEntry({
        path: "discord.bot-token",
        key: "bot-token",
        label: "Bot token",
        secret: true,
        environmentOverridden: false,
        value: "irrelevant",
      }),
    ])

    await search("bot token")

    expect(screen.queryByText("Bot token")).not.toBeNull()
  })

  it("shows nothing before anything is typed - not hundreds of settings on open", async () => {
    const loc = configLocation({ path: "steward/steward", name: "steward" })
    oneFile(loc, [configEntry({ path: "agent.base-url", key: "base-url", label: "Base url" })])

    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.queryByText("Base url")).toBeNull()
  })

  it("keeps the trailing grey column off a phone entirely", async () => {
    /** The path is hidden below 640px, where it would truncate the name; jsdom can only check the classes. */
    const loc = configLocation({ path: "steward/steward", name: "steward" })
    oneFile(loc, [configEntry({ path: "agent.base-url", key: "base-url", label: "Base url" })])

    await search("base url")
    await screen.findByText("Base url")

    const trailing = document.querySelectorAll('[data-slot="command-shortcut"]')
    expect(trailing.length).toBeGreaterThan(0)
    for (const column of trailing) {
      expect(column.className).toContain("hidden")
      expect(column.className).toContain("sm:block")
    }
  })

  it("selecting a hit of the network's settings navigates to the season page, where they are", async () => {
    const loc = configLocation({ path: "network/players", name: "players", service: "network" })
    oneFile(loc, [configEntry({ path: "max-players", key: "max-players", label: "Max players" })])

    await search("max players")
    fireEvent.click(await screen.findByText("Max players"))

    expect(navigateSpy).toHaveBeenCalledWith({ to: "/season", search: { file: "network/players" } })
    expect(takePendingJump("network")).toEqual({ file: "network/players", path: "max-players" })
  })

  it("selecting a hit navigates to that service's page and hands it a jump", async () => {
    const loc = configLocation({ path: "steward/steward", name: "steward" })
    oneFile(loc, [configEntry({ path: "agent.base-url", key: "base-url", label: "Base url" })])

    await search("base url")
    fireEvent.click(await screen.findByText("Base url"))

    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/services/$name",
      params: { name: "steward" },
      search: { tab: "settings", file: "steward/steward" },
    })
    expect(takePendingJump("steward")).toEqual({
      file: "steward/steward",
      path: "agent.base-url",
    })
  })
})

/** A text that lives only in a message bundle is still found. */
describe("CommandPalette - finding a message bundle key", () => {
  it("finds a bundle key that no config file mentions", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ texts: { en: ["Your grave has decayed."] }, key: "grave.decay.announce" })])

    await search("decayed")

    /** The row is named as the Settings tab names the text, with the matched text on the right. */
    expect(screen.queryByText("Announce")).not.toBeNull()
    expect(screen.queryByText(/Your grave has decayed\./)).not.toBeNull()
  })

  it("finds a key by its German translation, not only its English default", async () => {
    /** A synthetic marker rather than German prose, since `language.test.ts` scans fixtures too. */
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [
      messageEntry({
        texts: { en: ["Your grave has decayed."], de: ["packaged-de-marker"] },
        key: "grave.decay.announce",
      }),
    ])

    await search("de-marker")

    expect(screen.queryByText(/packaged-de-marker/)).not.toBeNull()
  })

  it("finds a key by the key itself", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ texts: { en: ["Your grave has decayed."] }, key: "grave.decay.announce" })])

    await search("grave.decay")

    // Matched by the key, which the row itself never shows.
    expect(screen.queryByText("Announce")).not.toBeNull()
    expect(screen.queryByText(/grave\.decay/)).toBeNull()
  })

  it("shows nothing before anything is typed, same as a config hit", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ texts: { en: ["Your grave has decayed."] }, key: "grave.decay.announce" })])

    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.queryByText("grave.decay.announce")).toBeNull()
  })

  it("selecting a bundle hit navigates to the service page and hands the messages tool a jump, not the configuration form", async () => {
    const loc = bundleLocation({ path: "smp/smp", service: "smp" })
    oneBundle(loc, [messageEntry({ texts: { en: ["Your grave has decayed."] }, key: "grave.decay.announce" })])

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
    // And never the config map: a bundle hit must not be mistaken for a config one downstream.
    expect(takePendingJump("smp")).toBeUndefined()
  })

  it("finds a config hit and a bundle hit together, in one list", async () => {
    const configLoc: ConfigLocation = {
      service: "smp",
      name: "steward",
      path: "smp/steward.yml",
      label: "",
      live: true,
      readable: true,
      writable: true,
    }
    const gravEntry: ConfigEntry = {
      path: "grave.decay.enabled",
      key: "enabled",
      label: "Grave decay enabled",
      explanation: "",
      noExplanationNeeded: false,
      filled: true,
      kind: "SCALAR",
      type: "BOOLEAN",
      editable: true,
      secret: false,
      environmentOverridden: false,
    }
    vi.mocked(useConfigs).mockReturnValue(queryResult([configLoc]))
    vi.mocked(useConfigDocuments).mockReturnValue([
      queryResult({ ...configLoc, revision: "r1", restartRequired: false, entries: [gravEntry] }),
    ])
    oneBundle(bundleLocation({ path: "smp/smp", service: "smp" }), [
      messageEntry({ texts: { en: ["Grave decay announcement"] }, key: "grave.decay.announce" }),
    ])

    await search("grave decay")

    expect(screen.queryByText("Grave decay enabled")).not.toBeNull()
    expect(screen.queryByText("Announce")).not.toBeNull()
  })
})

/** `cmdk`'s `role="combobox"` drops the placeholder as a name, so the field needs its own. */
describe("CommandPalette - the input says what it is", () => {
  it("is findable by role and name, not only by its placeholder", async () => {
    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.getByRole("combobox", { name: "Search pages, runs, settings" })).toBe(searchInput())
  })
})

/** A setting named "Donor" outranks every service page that only fuzzily contains those letters, in DOM order. */
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

/** Two rows with identical search text are still two rows to cmdk, so pointing at one lights only it. */
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

/** The palette is a bottom sheet on a phone; jsdom can say which shell mounted, not how it looks. */
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
    // The dialog's width and offset are the desktop half; on a sheet they would shrink it and push it aside.
    const sheet = nonNull(input.closest("[data-slot='drawer-content']"), "the drawer content")
    const bare = sheet.className.split(/\s+/).filter((name) => !name.includes(":"))
    expect(
      bare.some((name) => name.startsWith("w-[") || name.startsWith("top-[") || name.startsWith("translate-")),
    ).toBe(false)
  })
})
