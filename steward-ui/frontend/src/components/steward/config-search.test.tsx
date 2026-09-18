import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ServiceSettingsSearch } from "@/components/steward/config-search"
import {
  useConfigDocuments,
  useMessageBundles,
  useMessageDocuments,
} from "@/lib/queries"
import { takePendingMessageJump } from "@/lib/settings-search"
import type {
  ConfigDocument,
  ConfigEntry,
  ConfigLocation,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
} from "@/lib/api"

/**
 * steward/58's first box - search within one service's own settings - extended by steward/87 to
 * search that service's message bundles too.
 *
 * `useConfigDocuments`/`useMessageBundles`/`useMessageDocuments` are mocked rather than driven
 * through a real `QueryClientProvider` + fetch stub, the same style `command-palette.test.tsx`
 * already uses for the same reason: this is a test of what the box does with an answer, not of
 * TanStack Query itself.
 */
vi.mock("@/lib/queries", () => ({
  useConfigDocuments: vi.fn(),
  useMessageBundles: vi.fn(),
  useMessageDocuments: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(useConfigDocuments).mockReturnValue([])
  vi.mocked(useMessageBundles).mockReturnValue({ data: [] } as never)
  vi.mocked(useMessageDocuments).mockReturnValue([])
})

afterEach(() => {
  cleanup()
  vi.mocked(useConfigDocuments).mockReset()
  vi.mocked(useMessageBundles).mockReset()
  vi.mocked(useMessageDocuments).mockReset()
})

function location(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return { service: "smp", readable: true, writable: true, ...over }
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

function bundleLocation(
  over: Partial<MessageBundleLocation> & { path: string },
): MessageBundleLocation {
  return { service: "smp", module: "smp", writable: true, ...over }
}

function messageEntry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return { inBundle: true, ...over }
}

/** Wires one config file and one message bundle by index, the way the box actually pairs them. */
function wire(options: {
  files?: Array<{ location: ConfigLocation; document: ConfigDocument }>
  bundles?: Array<{ location: MessageBundleLocation; bundle: MessageBundle }>
}) {
  const files = options.files ?? []
  const bundles = options.bundles ?? []
  vi.mocked(useConfigDocuments).mockReturnValue(
    files.map(({ document }) => ({ data: document, isLoading: false })) as never,
  )
  vi.mocked(useMessageBundles).mockReturnValue({
    data: bundles.map(({ location: loc }) => loc),
  } as never)
  vi.mocked(useMessageDocuments).mockReturnValue(
    bundles.map(({ bundle }) => ({ data: bundle, isLoading: false })) as never,
  )
}

async function typeInto(placeholder: string, value: string) {
  const box = screen.getByPlaceholderText(placeholder)
  fireEvent.change(box, { target: { value } })
  return box
}

describe("ServiceSettingsSearch - config only, unchanged (steward/58)", () => {
  it("renders nothing when the service has no config files", () => {
    const { container } = render(<ServiceSettingsSearch files={[]} onJump={vi.fn()} />)
    expect(container.innerHTML).toBe("")
  })

  it("finds a config entry and hands the jump to onJump, not to the messages tool", async () => {
    const loc = location({ path: "smp/steward.yml", name: "steward.yml" })
    const document: ConfigDocument = {
      ...loc,
      revision: "r1",
      header: [],
      entries: [entry({ path: "farm.reset.enabled", key: "enabled", label: "Farm reset enabled" })],
    }
    wire({ files: [{ location: loc, document }] })
    const onJump = vi.fn()

    render(<ServiceSettingsSearch files={[loc]} onJump={onJump} />)
    await typeInto("Search this service's settings…", "farm reset")

    const hit = await screen.findByText("Farm reset enabled")
    fireEvent.click(hit)

    expect(onJump).toHaveBeenCalledWith("smp/steward.yml", "farm.reset.enabled")
  })
})

/**
 * steward/87: the second supplier. "A text that lives only in a bundle is not found before this
 * ticket, and is found after" is the ticket's own words - the first test below is exactly that
 * sentence for this box.
 */
describe("ServiceSettingsSearch - message bundles too (steward/87)", () => {
  it("finds a bundle key that no config file of this service mentions", async () => {
    const loc = location({ path: "smp/steward.yml", name: "steward.yml" })
    const document: ConfigDocument = { ...loc, revision: "r1", header: [], entries: [] }
    const bundleLoc = bundleLocation({ path: "smp/smp" })
    const bundle: MessageBundle = {
      ...bundleLoc,
      entries: [messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." })],
    }
    wire({ files: [{ location: loc, document }], bundles: [{ location: bundleLoc, bundle }] })

    render(<ServiceSettingsSearch files={[loc]} onJump={vi.fn()} />)
    await typeInto("Search this service's settings…", "resetting")

    expect(await screen.findByText("farm.reset.announce")).not.toBeNull()
  })

  it("shows the four-part location for a bundle hit - module, language and key", async () => {
    const loc = location({ path: "smp/steward.yml", name: "steward.yml" })
    const document: ConfigDocument = { ...loc, revision: "r1", header: [], entries: [] }
    const bundleLoc = bundleLocation({ path: "smp/smp", module: "smp" })
    const bundle: MessageBundle = {
      ...bundleLoc,
      entries: [messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." })],
    }
    wire({ files: [{ location: loc, document }], bundles: [{ location: bundleLoc, bundle }] })

    render(<ServiceSettingsSearch files={[loc]} onJump={vi.fn()} />)
    await typeInto("Search this service's settings…", "resetting")
    await screen.findByText("farm.reset.announce")

    // Module and language both show; the service itself does not (the box is already scoped to
    // one service, the same way a config hit's row omits it too - `showService` defaults to false).
    expect(screen.getByText("EN")).not.toBeNull()
    expect(screen.getAllByText("smp").length).toBe(1) // the module segment, not a repeated service
  })

  it("selecting a bundle hit sets a pending message jump instead of calling onJump", async () => {
    const loc = location({ path: "smp/steward.yml", name: "steward.yml" })
    const document: ConfigDocument = { ...loc, revision: "r1", header: [], entries: [] }
    const bundleLoc = bundleLocation({ path: "smp/smp", service: "smp" })
    const bundle: MessageBundle = {
      ...bundleLoc,
      entries: [messageEntry({ key: "farm.reset.announce", english: "The farm world is resetting." })],
    }
    wire({ files: [{ location: loc, document }], bundles: [{ location: bundleLoc, bundle }] })
    const onJump = vi.fn()

    render(<ServiceSettingsSearch files={[loc]} onJump={onJump} />)
    await typeInto("Search this service's settings…", "resetting")
    fireEvent.click(await screen.findByText("farm.reset.announce"))

    expect(onJump).not.toHaveBeenCalled()
    expect(takePendingMessageJump("smp")).toEqual({
      path: "smp/smp",
      language: "en",
      key: "farm.reset.announce",
    })
  })

  it("finds a key by its German translation as well as its English default", async () => {
    // A synthetic marker, not real German prose - `language.test.ts` scans every source file for
    // German and a fixture is not exempt from that, the same reason `messages.test.tsx` (steward/48)
    // spells its own German fixtures as "packaged-de-text" rather than an actual sentence.
    const loc = location({ path: "smp/steward.yml", name: "steward.yml" })
    const document: ConfigDocument = { ...loc, revision: "r1", header: [], entries: [] }
    const bundleLoc = bundleLocation({ path: "smp/smp" })
    const bundle: MessageBundle = {
      ...bundleLoc,
      entries: [
        messageEntry({
          key: "farm.reset.announce",
          english: "The farm world is resetting.",
          german: "packaged-de-marker",
        }),
      ],
    }
    wire({ files: [{ location: loc, document }], bundles: [{ location: bundleLoc, bundle }] })

    render(<ServiceSettingsSearch files={[loc]} onJump={vi.fn()} />)
    await typeInto("Search this service's settings…", "de-marker")

    expect(await screen.findByText("farm.reset.announce")).not.toBeNull()
  })

  it("shows one list, not two groups - a config hit and a bundle hit both matching the same query", async () => {
    const loc = location({ path: "smp/steward.yml", name: "steward.yml" })
    const document: ConfigDocument = {
      ...loc,
      revision: "r1",
      header: [],
      entries: [entry({ path: "farm.reset.enabled", key: "enabled", label: "Farm reset enabled" })],
    }
    const bundleLoc = bundleLocation({ path: "smp/smp" })
    const bundle: MessageBundle = {
      ...bundleLoc,
      entries: [messageEntry({ key: "farm.reset.announce", english: "Farm reset announcement" })],
    }
    wire({ files: [{ location: loc, document }], bundles: [{ location: bundleLoc, bundle }] })

    render(<ServiceSettingsSearch files={[loc]} onJump={vi.fn()} />)
    await typeInto("Search this service's settings…", "farm reset")

    expect(await screen.findByText("Farm reset enabled")).not.toBeNull()
    expect(await screen.findByText("farm.reset.announce")).not.toBeNull()
  })
})

/**
 * steward/105: the same ranking fault as the palette's, one service wide.
 *
 * This box never had a fuzzy filter to go wrong - it lists what {@link searchAcross} returned, in
 * file order. `donation-cents` is written above `roles.donor` in `discord-bot/access.yml`, and its
 * explanation mentions the donor role, so typing the name of a setting put a setting that merely
 * talks about it first. Measured on this host, 2026-09-18, before the change.
 */
describe("ServiceSettingsSearch - an exact name comes first (steward/105)", () => {
  it("puts Donor above the setting whose explanation only mentions a donor", async () => {
    const loc = location({ path: "discord-bot/access.yml", name: "access.yml", service: "discord-bot" })
    const document: ConfigDocument = {
      ...loc,
      revision: "r1",
      header: [],
      entries: [
        entry({
          path: "donation-cents",
          key: "donation-cents",
          label: "Donation cents",
          explanation:
            "The extra amount that grants the donor role - also how a payment above the order total is recognised as a donation.",
          value: "500",
        }),
        entry({
          path: "roles.donor",
          key: "donor",
          label: "Donor",
          explanation: "Granted on a donation and never revoked - safe to hand out manually in Discord.",
          value: "1544515504889139301",
        }),
      ],
    }
    wire({ files: [{ location: loc, document }] })

    render(<ServiceSettingsSearch files={[loc]} onJump={vi.fn()} />)
    await typeInto("Search this service's settings…", "Donor")

    // Every hit row, top to bottom: the box draws one button per hit and nothing else.
    const rows = screen.getAllByRole("button").map((row) => (row.textContent ?? "").trim())
    expect(rows.findIndex((row) => row.startsWith("Donor"))).toBe(0)
    expect(rows.findIndex((row) => row.startsWith("Donation cents"))).toBe(1)
  })
})
