import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ServiceConfiguration } from "@/components/steward/configuration"
import type { ConfigEntry, ConfigLocation } from "@/lib/api"

/**
 * The configuration form draws headings, labels and controls out of what steward/55's worker now
 * sends - the schema beside a file, when there is one - and this is where that drawing is checked,
 * the same way `recreate.test.tsx` checks a dialog against a fake backend answering by URL.
 *
 * Every scenario below is a RED test first: each one failed against the form as it stood before
 * steward/56 (raw filenames, comments-only text, no "not in schema" marker, no select for a
 * schema's allowed values, `database.yml` an ordinary writable file, no route for a file that will
 * not parse as YAML at all) and is recorded failing, verbatim, in the ticket's report before the
 * component below was written to make it pass.
 *
 * No `jest-dom`, same as `recreate.test.tsx` - this project does not install it, so an assertion
 * like "disabled" reads the DOM property directly rather than through a matcher this repo has
 * deliberately not added.
 */

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

function location(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return {
    service: "steward-worker",
    readable: true,
    writable: true,
    ...over,
  }
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

const GUILD_UNAVAILABLE = { available: false, reason: "no bot token in this test", entries: [] }

/** One `/api/config/<path>` answer per fixture file, keyed exactly the way the route is called. */
function backend(documents: Record<string, unknown>) {
  const listing = Object.values(documents).map((document) => {
    const { service, name, path, readable, writable } = document as ConfigLocation
    return { service, name, path, readable, writable }
  })
  return vi.fn(async (url: string) => {
    if (url === "/api/config") return json(listing)
    if (url === "/api/discord/roles" || url === "/api/discord/channels") return json(GUILD_UNAVAILABLE)
    const found = Object.entries(documents).find(([path]) => url === `/api/config/${path}`)
    if (found) return json(found[1])
    throw new Error(`the form asked for ${url}, which this test did not expect`)
  })
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>)
}

/** Opens a file row by the plain-text name the row now shows (steward/56), not the raw filename. */
async function open(humanName: string) {
  fireEvent.click(await screen.findByText(humanName))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("the file row", () => {
  it("shows the plain-text file name, and keeps the raw one beside it", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "steward-worker/steward.yml": {
          ...location({ path: "steward-worker/steward.yml", name: "steward.yml" }),
          revision: "r1",
          header: [],
          entries: [],
        },
      }),
    )

    draw(<ServiceConfiguration service="steward-worker" />)

    await screen.findByText("Steward")
    screen.getByText("steward.yml")
  })
})

/**
 * steward/58: a search box confined to one service's own files - label, key path, current value
 * and explanation text, a secret's value excluded from all of it, and a hit that lands on the field
 * and lights it up rather than merely opening the file it lives in.
 */
describe("searching a service's settings (steward/58)", () => {
  const file = "steward-worker/steward.yml"

  function withEntries(entries: ConfigEntry[]) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          entries,
        },
      }),
    )
  }

  function searchBox() {
    return screen.getByLabelText("Search this service's settings")
  }

  it("finds a setting by its label without the file being open first", async () => {
    withEntries([
      entry({ path: "worker.base-url", key: "base-url", label: "Base url", value: "http://steward-worker:8081" }),
    ])
    draw(<ServiceConfiguration service="steward-worker" />)
    await screen.findByText("Steward")

    fireEvent.change(searchBox(), { target: { value: "base url" } })

    await screen.findByText(/worker\.base-url/)
  })

  it("finds a setting by its current value", async () => {
    withEntries([
      entry({ path: "worker.base-url", key: "base-url", label: "Base url", value: "http://steward-worker:8081" }),
    ])
    draw(<ServiceConfiguration service="steward-worker" />)
    await screen.findByText("Steward")

    fireEvent.change(searchBox(), { target: { value: "8081" } })

    await screen.findByText(/worker\.base-url/)
  })

  it("finds a setting by its explanation text", async () => {
    withEntries([
      entry({
        path: "limits.max-attempts",
        key: "max-attempts",
        label: "Max attempts",
        explanation: "How many times a failed job is retried before it is given up on.",
      }),
    ])
    draw(<ServiceConfiguration service="steward-worker" />)
    await screen.findByText("Steward")

    fireEvent.change(searchBox(), { target: { value: "given up" } })

    await screen.findByText("Max attempts")
  })

  it("RED, then fixed: a secret's known value must never surface a hit", async () => {
    const token = "MTA1NzE4.super-secret-discord-token"
    withEntries([
      // As if a future bug sent a value for a secret anyway - the wire contract in lib/api.ts says
      // this never happens, and the search box has to refuse it on its own regardless.
      entry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: token }),
    ])
    draw(<ServiceConfiguration service="steward-worker" />)
    await screen.findByText("Steward")

    fireEvent.change(searchBox(), { target: { value: token } })

    await screen.findByText("Nothing found.")
    expect(screen.queryByText("Bot token")).toBeNull()
  })

  it("still finds that secret entry by its label - only the value is excluded", async () => {
    withEntries([
      entry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: "irrelevant" }),
    ])
    draw(<ServiceConfiguration service="steward-worker" />)
    await screen.findByText("Steward")

    fireEvent.change(searchBox(), { target: { value: "bot token" } })

    await screen.findByText(/discord\.bot-token/)
  })

  it("a hit opens the file and highlights the field, not just the file", async () => {
    withEntries([
      entry({ path: "worker.base-url", key: "base-url", label: "Base url", value: "http://steward-worker:8081" }),
    ])
    const { container } = draw(<ServiceConfiguration service="steward-worker" />)
    await screen.findByText("Steward")

    // Not open yet - this is the whole point: the box finds the field before anybody expands
    // the file it lives in.
    expect(screen.queryByText("http://steward-worker:8081")).toBeNull()

    fireEvent.change(searchBox(), { target: { value: "base url" } })
    fireEvent.click(await screen.findByText(/worker\.base-url/))

    await screen.findByDisplayValue("http://steward-worker:8081")
    expect(container.querySelector(".ring-primary")).not.toBeNull()

    // The search box clears and its results close once a hit has been taken.
    expect((searchBox() as HTMLInputElement).value).toBe("")
  })
})

describe("headings and explanations", () => {
  const file = "steward-worker/steward.yml"

  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          entries: [
            entry({ path: "worker", key: "worker", label: "Worker", kind: "MAP" }),
            entry({ path: "worker.limits", key: "limits", label: "Limits", kind: "MAP" }),
            // Three levels deep: steward/56 says a heading stops here and the leaf's own path
            // carries the rest instead of a fourth grouping concept.
            entry({ path: "worker.limits.retry", key: "retry", label: "Retry", kind: "MAP" }),
            entry({
              path: "worker.limits.retry.max-attempts",
              key: "max-attempts",
              label: "Max attempts",
              value: "3",
              type: "INTEGER",
              explanation: "How many times a failed job is retried before it is given up on.",
            }),
            entry({
              path: "silent",
              key: "silent",
              label: "Silent",
              comments: ["An old mechanical comment that must not show through."],
              noExplanationNeeded: true,
            }),
            entry({
              path: "mechanical",
              key: "mechanical",
              label: "Mechanical",
              comments: ["Whatever jcore's comment block above this key used to say."],
            }),
            entry({ path: "orphan", key: "orphan", label: "Orphan", inSchema: false }),
          ],
        },
      }),
    )
  })

  it("draws a heading for the first two levels of YAML nesting", async () => {
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")

    await screen.findByRole("heading", { name: "Worker" })
    screen.getByRole("heading", { name: "Limits" })
  })

  it("stops heading at the third level and lets the leaf's own path name the rest", async () => {
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")
    await screen.findByRole("heading", { name: "Worker" })

    expect(screen.queryByRole("heading", { name: "Retry" })).toBeNull()
    screen.getByText("worker.limits.retry.max-attempts")
  })

  it("shows the schema's explanation under a label", async () => {
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")

    await screen.findByText("How many times a failed job is retried before it is given up on.")
  })

  it("draws literally no text when the schema says no explanation is needed", async () => {
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")
    await screen.findByRole("heading", { name: "Worker" })

    expect(
      screen.queryByText("An old mechanical comment that must not show through."),
    ).toBeNull()
  })

  it("falls back to the mechanical comment when there is no schema explanation", async () => {
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")

    await screen.findByText("Whatever jcore's comment block above this key used to say.")
  })

  it("marks a key the schema does not cover as not in schema", async () => {
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")

    await screen.findByText("not in schema")
  })
})

describe("a schema's allowed values", () => {
  const file = "steward-worker/steward.yml"

  it("becomes a closed select with no free text when strict", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          entries: [
            entry({
              path: "region",
              key: "region",
              label: "Region",
              value: "eu",
              choices: { values: ["eu", "us"], strict: true },
            }),
          ],
        },
      }),
    )
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")

    const combobox = await screen.findByRole("combobox")
    expect(combobox.textContent).toContain("eu")
    expect(screen.queryByLabelText("Free text")).toBeNull()
  })

  it("is a suggestion beside a free-text field when not strict", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          entries: [
            entry({
              path: "color",
              key: "color",
              label: "Color",
              value: "custom-magenta",
              choices: { values: ["red", "green", "blue"], strict: false },
            }),
          ],
        },
      }),
    )
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Steward")

    await screen.findByRole("combobox")
    const free = screen.getByLabelText("Free text") as HTMLInputElement
    expect(free.value).toBe("custom-magenta")
  })
})

describe("database.yml", () => {
  it("is visibly read-only even though the mount underneath it is writable", async () => {
    const file = "steward-worker/database.yml"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "database.yml", writable: true }),
          revision: "r1",
          header: [],
          entries: [entry({ path: "host", key: "host", label: "Host", value: "postgres" })],
        },
      }),
    )
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Database")

    await screen.findByText("This file is read-only in Steward.")
    const save = screen.getByRole("button", { name: /Save/ }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
  })

  it("is read-only for a plugin too, whose file is not called database.yml on its own", async () => {
    // `name` is the path under the service directory, so only the three services that keep their
    // file at the top - discord-bot, steward-worker, steward-ui - are called `database.yml`
    // outright. A plugin's is `smp/database.yml`. Measured against the running mount on
    // 2026-09-16, an equality check caught three of seven and left the other four editable.
    const file = "smp/smp/database.yml"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "smp/database.yml", service: "smp", writable: true }),
          revision: "r1",
          header: [],
          entries: [entry({ path: "host", key: "host", label: "Host", value: "postgres" })],
        },
      }),
    )
    draw(<ServiceConfiguration service="smp" />)
    await open("Smp database")

    await screen.findByText("This file is read-only in Steward.")
    const save = screen.getByRole("button", { name: /Save/ }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
  })
})

describe("a file that does not parse as YAML", () => {
  it("is shown as raw, read-only text instead of an error", async () => {
    const file = "steward-worker/README.txt"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "README.txt" }),
          raw: true,
          reason: "line 1: the top of the file must be a set of keys, found a scalar instead",
          content: "Read me.\n",
        },
      }),
    )
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Readme")

    await screen.findByText("Shown as raw text.")
    // `getByDisplayValue`'s default normalizer trims trailing whitespace, so the trailing newline
    // the fixture's content ends in is not part of what it matches against.
    screen.getByDisplayValue("Read me.")
    expect(screen.queryByRole("button", { name: /Save/ })).toBeNull()
  })
})

describe("a file with no schema at all", () => {
  it("is not marked raw - it still gets the ordinary mechanical form", async () => {
    const file = "steward-worker/legacy.yml"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "legacy.yml" }),
          revision: "r1",
          header: [],
          entries: [
            entry({
              path: "port",
              key: "port",
              label: "Port",
              value: "8080",
              type: "INTEGER",
              comments: ["Mechanical, no schema wrote this file."],
            }),
          ],
        },
      }),
    )
    draw(<ServiceConfiguration service="steward-worker" />)
    await open("Legacy")

    await screen.findByText("Mechanical, no schema wrote this file.")
    expect(screen.queryByText("Shown as raw text.")).toBeNull()
    screen.getByText("Port")
  })
})

/**
 * Repeatable cards (steward/57), wired through the real form rather than tested in isolation the
 * way `repeatable-cards.test.tsx` does it - these prove `Control` actually reaches for
 * `RepeatableCards` on a `SECTIONS` entry, and that the whole page still only writes on Save.
 *
 * The worker does not send `kind: "SECTIONS"` today - see the comment on `ConfigEntry.kind` in
 * `lib/api.ts` - so every fixture below is this ticket's own proposal for the shape, exercised the
 * same way steward/56's fixtures stood in for a worker change that had already shipped by the time
 * they were written. Here it has not.
 */
describe("repeatable cards for a SECTIONS entry (steward/57)", () => {
  const file = "discord-bot/access.yml"
  const TEMPLATE: ConfigEntry[] = [
    entry({ path: "tag", key: "tag", label: "Tag" }),
    entry({ path: "contribution-channel", key: "contribution-channel", label: "Contribution channel" }),
  ]

  function section(tag: string, channel: string): ConfigEntry[] {
    return [
      entry({ path: "tag", key: "tag", value: tag }),
      entry({ path: "contribution-channel", key: "contribution-channel", value: channel }),
    ]
  }

  function languages(sections: ConfigEntry[][]): ConfigEntry {
    return entry({
      path: "languages",
      key: "languages",
      label: "Languages",
      kind: "SECTIONS",
      editable: true,
      template: TEMPLATE,
      sections,
    })
  }

  it("draws one card per section and adds a blank one on request", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          entries: [languages([section("en", "")])],
        },
      }),
    )
    draw(<ServiceConfiguration service="discord-bot" />)
    await open("Access")

    await screen.findByDisplayValue("en")
    fireEvent.click(screen.getByRole("button", { name: /Add entry/ }))

    const tags = screen.getAllByLabelText("Tag") as HTMLInputElement[]
    expect(tags.map((input) => input.value)).toEqual(["en", ""])
  })

  it("gives a channel field inside a card the SnowflakePicker, not a plain text box", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          entries: [languages([section("en", "")])],
        },
      }),
    )
    draw(<ServiceConfiguration service="discord-bot" />)
    await open("Access")

    // GUILD_UNAVAILABLE (no bot token in this test) makes the picker degrade to a text input, but
    // it still carries its own fallback hint - a plain ScalarControl text field never shows this.
    await screen.findByText(/Paste the id instead/)
  })

  it("removes a card from the draft only, and writes it on Save - not on the click", async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/api/config") {
        return json([
          { service: "discord-bot", name: "access.yml", path: file, readable: true, writable: true },
        ])
      }
      if (url === "/api/discord/roles" || url === "/api/discord/channels") return json(GUILD_UNAVAILABLE)
      if (url === `/api/config/${file}` && init?.method === "PUT") {
        const body = JSON.parse(String(init.body)) as { revision: string; changes: Record<string, unknown> }
        expect(body.changes.languages).toEqual([{ tag: "en", "contribution-channel": "" }])
        return json({
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r2",
          header: [],
          entries: [languages([section("en", "")])],
        })
      }
      if (url === `/api/config/${file}`) {
        return json({
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          entries: [languages([section("en", ""), section("de", "")])],
        })
      }
      throw new Error(`the form asked for ${url}, which this test did not expect`)
    })
    vi.stubGlobal("fetch", fetchMock)

    draw(<ServiceConfiguration service="discord-bot" />)
    await open("Access")
    await screen.findByDisplayValue("de")

    fireEvent.click(screen.getByRole("button", { name: "Remove entry 2" }))

    // The card is gone from the draft, and the count says so - but nothing has been written yet.
    expect(screen.queryByDisplayValue("de")).toBeNull()
    screen.getByText("One setting changed.")
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false)

    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true),
    )
  })
})
