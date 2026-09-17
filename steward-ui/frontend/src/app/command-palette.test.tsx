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
} from "@/lib/queries"
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
 * The second of `steward/04`'s five findings: the comment said the browser keeps its own Ctrl+K
 * while somebody is typing, and the code did the opposite - every Ctrl+K in a console line or a
 * config field was swallowed and opened the search instead. It was fixed with `tsc` and thinking,
 * and this is the test that was missing.
 *
 * The palette's own input is the deliberate exception: there the shortcut is how you close it
 * again, so the rule is *not while typing, unless the palette is already open*. That "unless" is
 * why this cannot be a test of `isEditable` alone - the bug was the condition at the call site, and
 * an inverted condition passes every unit test of the predicate under it.
 */

// The palette navigates on select. `navigateSpy` is asserted on by the steward/52 tests below;
// the Ctrl+K tests above never select anything, so they never look at it.
const navigateSpy = vi.fn()
vi.mock("@tanstack/react-router", () => ({ useNavigate: () => navigateSpy }))

// steward/52: the palette now loads runs to make them findable by more than their page title.
// steward/58 adds the settings search the same way: mocked rather than driven through a real
// QueryClientProvider + fetch stub, matching this file's existing style of mocking a dependency
// rather than integrating the whole stack. steward/87 adds the message-bundle pair the same way
// again - `useMessageBundles`/`useMessageDocuments` mirror `useConfigs`/`useConfigDocuments` one
// for one.
vi.mock("@/lib/queries", () => ({
  useRuns: vi.fn(),
  useConfigs: vi.fn(),
  useConfigDocuments: vi.fn(),
  useMessageBundles: vi.fn(),
  useMessageDocuments: vi.fn(),
}))

beforeEach(() => {
  // The default every test not about runs or settings gets: nothing loaded, so the palette behaves
  // exactly as it did before steward/52 unless a test asks for one of them specifically.
  vi.mocked(useRuns).mockReturnValue({ data: [] } as never)
  vi.mocked(useConfigs).mockReturnValue({ data: [] } as never)
  vi.mocked(useConfigDocuments).mockReturnValue([])
  vi.mocked(useMessageBundles).mockReturnValue({ data: [] } as never)
  vi.mocked(useMessageDocuments).mockReturnValue([])
})

afterEach(() => {
  cleanup()
  navigateSpy.mockClear()
  vi.mocked(useRuns).mockReset()
  vi.mocked(useConfigs).mockReset()
  vi.mocked(useConfigDocuments).mockReset()
  vi.mocked(useMessageBundles).mockReset()
  vi.mocked(useMessageDocuments).mockReset()
})

/** The palette, identified by the one thing only the open dialog has. */
const searchInput = () => screen.queryByPlaceholderText("Search pages, runs, settings…")

function ctrlK(target: Element | Document) {
  fireEvent.keyDown(target, { key: "k", ctrlKey: true })
}

/** A run, shaped like `/api/updates` answers it - only the fields this file's tests look at vary. */
function run(over: Partial<Run> = {}): Run {
  return {
    id: 91,
    kind: "BACKUP",
    status: "FAILED",
    source: "SCHEDULE",
    requestedBy: "clock",
    requested: "2026-09-16T04:45:00Z",
    notBefore: "2026-09-16T04:45:00Z",
    started: "2026-09-16T04:45:00Z",
    finished: "2026-09-16T04:46:12Z",
    ...over,
  }
}

/** Opens the palette and types into it - the setup every steward/52 test starts from. */
async function search(query: string) {
  render(<CommandPalette />)
  ctrlK(document.body)
  const input = await waitFor(() => {
    const found = searchInput()
    expect(found).not.toBeNull()
    return found as HTMLElement
  })
  fireEvent.change(input, { target: { value: query } })
  return input
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
    const input = searchInput()
    expect(input).not.toBeNull()
    ctrlK(input as Element)
    await waitFor(() => expect(searchInput()).toBeNull())
  })
})

/**
 * steward/52: the palette found page titles and nothing else.
 *
 * `testrunde-2026-09-15.md` point 11: Till typed "report" wanting the backup report and did not
 * land on it, because a run was never in the palette to begin with - only the four groups of pages
 * in `navigation.ts`, none of which is called "report". These tests load a run into the mocked
 * `useRuns` and check that it becomes findable by the four things `steward/52` names: its number,
 * its kind, its outcome and a synonym nobody would find in an English page title - German included
 * (imported from `run-search-terms.ts` rather than spelled out here, which is what keeps this file
 * out of `language.test.ts`'s exemption list - see that file's `EXEMPT` set).
 */
describe("CommandPalette - finding a run (steward/52)", () => {
  it("still finds a page by its title - the behaviour before this ticket, unchanged", async () => {
    await search("restore")
    expect(screen.queryByText("Restore")).not.toBeNull()
  })

  it('finds the failed backup run on "report" - Till\'s own sentence', async () => {
    vi.mocked(useRuns).mockReturnValue({ data: [run()] } as never)
    await search("report")
    expect(screen.queryByText(/Run #91/)).not.toBeNull()
  })

  it("takes German with it: the German synonym on the backup entry finds the same run", async () => {
    vi.mocked(useRuns).mockReturnValue({ data: [run()] } as never)
    await search(GERMAN_BACKUP_SYNONYM)
    expect(screen.queryByText(/Run #91/)).not.toBeNull()
  })

  it("makes a run findable by its outcome, not only by its kind", async () => {
    vi.mocked(useRuns).mockReturnValue({
      data: [run({ id: 12, kind: "UPDATE", status: "FAILED" })],
    } as never)
    await search("failed")
    expect(screen.queryByText(/Run #12/)).not.toBeNull()
  })

  it("makes a run findable by its kind, e.g. a restart", async () => {
    vi.mocked(useRuns).mockReturnValue({
      data: [run({ id: 7, kind: "RESTART", status: "DONE" })],
    } as never)
    await search("restart")
    expect(screen.queryByText(/Run #7/)).not.toBeNull()
  })

  it('selecting a run entry navigates to that run, not to "latest"', async () => {
    vi.mocked(useRuns).mockReturnValue({ data: [run({ id: 91 })] } as never)
    await search("report")
    const item = await screen.findByText(/Run #91/)
    fireEvent.click(item)
    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/operations/runs/$id",
      params: { id: "91" },
    })
  })
})

/**
 * steward/58: the second box the ticket asked for - a search across every service's settings, with
 * the service named in the hit, reachable from anywhere the same way a page or a run already is.
 */
describe("CommandPalette - finding a setting (steward/58)", () => {
  function location(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
    return { service: "steward-worker", readable: true, writable: true, ...over }
  }

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

  /** Wires `useConfigs`/`useConfigDocuments` for one file, the way the palette actually pairs them:
   * by index, in the order `locations` came back in. */
  function oneFile(loc: ConfigLocation, entries: ConfigEntry[]) {
    vi.mocked(useConfigs).mockReturnValue({ data: [loc] } as never)
    const document: ParsedConfigDocument = { ...loc, revision: "r1", header: [], entries }
    vi.mocked(useConfigDocuments).mockReturnValue([{ data: document, isLoading: false }] as never)
  }

  it("finds a setting by its label and names the service it belongs to", async () => {
    const loc = location({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [entry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    await search("base url")

    expect(screen.queryByText("Base url")).not.toBeNull()
    expect(screen.queryByText(/steward-worker/)).not.toBeNull()
  })

  it("finds a setting by its current value", async () => {
    const loc = location({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [
      entry({ path: "worker.base-url", key: "base-url", label: "Base url", value: "http://steward-worker:8081" }),
    ])

    await search("8081")

    expect(screen.queryByText("Base url")).not.toBeNull()
  })

  it("never finds a secret by its value, even when it is the only thing typed", async () => {
    const loc = location({ path: "discord-bot/steward.yml", name: "steward.yml", service: "discord-bot" })
    const token = "super-secret-discord-token"
    oneFile(loc, [
      // As if a future bug sent a value for a secret anyway - the client's own guard has to hold
      // regardless of what the wire happened to include.
      entry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: token }),
    ])

    await search(token)

    expect(screen.queryByText("Bot token")).toBeNull()
  })

  it("still finds that same secret entry by its label - only the value is excluded", async () => {
    const loc = location({ path: "discord-bot/steward.yml", name: "steward.yml", service: "discord-bot" })
    oneFile(loc, [
      entry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: "irrelevant" }),
    ])

    await search("bot token")

    expect(screen.queryByText("Bot token")).not.toBeNull()
  })

  it("shows nothing before anything is typed - not hundreds of settings on open", async () => {
    const loc = location({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [entry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.queryByText("Base url")).toBeNull()
  })

  it("selecting a hit navigates to that service's page and hands it a jump", async () => {
    const loc = location({ path: "steward-worker/steward.yml", name: "steward.yml" })
    oneFile(loc, [entry({ path: "worker.base-url", key: "base-url", label: "Base url" })])

    await search("base url")
    fireEvent.click(await screen.findByText("Base url"))

    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/services/$name",
      params: { name: "steward-worker" },
    })
    expect(takePendingJump("steward-worker")).toEqual({
      file: "steward-worker/steward.yml",
      path: "worker.base-url",
    })
  })
})

/**
 * steward/87: the message bundles of steward/48 are a second supplier for the same global search -
 * "a text that lives only in a bundle is not found before this ticket, and is found after" is the
 * ticket's own red-then-green sentence, and `finds a bundle key that no config file mentions` below
 * is that exact case, word for word.
 */
describe("CommandPalette - finding a message bundle key (steward/87)", () => {
  function bundleLocation(
    over: Partial<MessageBundleLocation> & { path: string },
  ): MessageBundleLocation {
    return { service: "smp", module: "smp", writable: true, ...over }
  }

  function messageEntry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
    return { inBundle: true, ...over }
  }

  /** Wires `useMessageBundles`/`useMessageDocuments` for one bundle, the same pairing-by-index
   * `oneFile` above does for a config file. */
  function oneBundle(loc: MessageBundleLocation, entries: MessageEntry[]) {
    vi.mocked(useMessageBundles).mockReturnValue({ data: [loc] } as never)
    const document: MessageBundle = { ...loc, entries }
    vi.mocked(useMessageDocuments).mockReturnValue([{ data: document, isLoading: false }] as never)
  }

  it("finds a bundle key that no config file mentions - the ticket's own red-then-green case", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [
      messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." }),
    ])

    await search("resetting")

    // The row's main label is the matched text, not the key - the same split a config hit already
    // draws between its human `label` and its technical `path` (steward/87, see command-palette.tsx
    // and config-search.tsx's `SettingsHitRow`). The key still rides along in the shortcut text.
    expect(screen.queryByText("The farm world is resetting.")).not.toBeNull()
  })

  it("finds a key by its German translation, not only its English default", async () => {
    // A synthetic marker, not real German prose - `language.test.ts` scans every source file for
    // German and a fixture is not exempt from that, the same reason `messages.test.tsx` (steward/48)
    // spells its own German fixtures as "packaged-de-text" rather than an actual sentence.
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [
      messageEntry({
        key: "farm.reset.announce",
        english: "The farm world is resetting.",
        german: "packaged-de-marker",
      }),
    ])

    await search("de-marker")

    expect(screen.queryByText("packaged-de-marker")).not.toBeNull()
  })

  it("finds a key by the key itself", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." })])

    await search("farm.reset")

    // Matched by the key (there is no German text on this entry, so only the English row survives
    // `searchMessagesAcross`'s per-language guard) - the visible label is still the English text,
    // and the key that was actually typed shows up in the shortcut instead.
    expect(screen.queryByText("The farm world is resetting.")).not.toBeNull()
  })

  it("shows nothing before anything is typed, same as a config hit", async () => {
    const loc = bundleLocation({ path: "smp/smp" })
    oneBundle(loc, [messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." })])

    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    expect(screen.queryByText("farm.reset.announce")).toBeNull()
  })

  it("selecting a bundle hit navigates to the service page and hands the messages tool a jump, not the configuration form", async () => {
    const loc = bundleLocation({ path: "smp/smp", service: "smp" })
    oneBundle(loc, [messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." })])

    await search("resetting")
    fireEvent.click(await screen.findByText("The farm world is resetting."))

    expect(navigateSpy).toHaveBeenCalledWith({
      to: "/services/$name",
      params: { name: "smp" },
    })
    expect(takePendingMessageJump("smp")).toEqual({
      path: "smp/smp",
      language: "en",
      key: "farm.reset.announce",
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
    const configEntry: ConfigEntry = {
      path: "farm.reset.enabled",
      key: "enabled",
      label: "Farm reset enabled",
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
    vi.mocked(useConfigs).mockReturnValue({ data: [configLoc] } as never)
    vi.mocked(useConfigDocuments).mockReturnValue([
      {
        data: { ...configLoc, revision: "r1", header: [], entries: [configEntry] },
        isLoading: false,
      },
    ] as never)
    oneBundle(bundleLocation({ path: "smp/smp", service: "smp" }), [
      messageEntry({ key: "farm.reset.announce", english: "Farm reset announcement" }),
    ])

    await search("farm reset")

    expect(screen.queryByText("Farm reset enabled")).not.toBeNull()
    expect(screen.queryByText("Farm reset announcement")).not.toBeNull()
  })
})

/**
 * steward/100: the field had no accessible name at all.
 *
 * `cmdk` gives the input `role="combobox"`, and that overrides the native textbox role. The
 * browser's placeholder-as-name fallback (HTML-AAM) applies to the native role only, so the
 * placeholder stopped counting the moment the role was set - a screen reader announced "combobox"
 * and nothing about what it searches. Nothing in this file caught it, because every test here finds
 * the field by `queryByPlaceholderText`, which asks about an attribute rather than about the name.
 *
 * Found by `preview.mjs` while building workspace/05: the tool looks a field up by accessible name
 * and fails loudly when it finds none, which is the whole reason the option is worth having.
 */
describe("CommandPalette - the input says what it is", () => {
  it("is findable by role and name, not only by its placeholder", async () => {
    render(<CommandPalette />)
    ctrlK(document.body)
    await waitFor(() => expect(searchInput()).not.toBeNull())

    // eslint-disable-next-line no-console
    expect(screen.getByRole("combobox", { name: "Search pages, runs, settings" })).toBe(
      searchInput(),
    )
  })
})
