import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ServiceSettings } from "@/components/steward/settings"
import { resetDrafts } from "@/lib/drafts"
import { setPendingJump, takePendingJump } from "@/lib/settings-search"
import type { ConfigEntry, ConfigLocation } from "@/lib/api"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The configuration form draws headings, labels and controls out of the schema beside a file, when
 * there is one, and this is where that drawing is checked, the same way `recreate.test.tsx` checks
 * a dialog against a fake backend answering by URL.
 *
 * No `jest-dom` - this project does not install it, so an assertion like "disabled" reads the DOM
 * property directly rather than through a matcher this repo has deliberately not added.
 */

async function search(query: string) {
  fireEvent.change(await screen.findByLabelText("Search this file"), { target: { value: query } })
}

function asInput(element: HTMLElement): HTMLInputElement {
  if (!(element instanceof HTMLInputElement)) throw new Error("expected an input element")
  return element
}

function asTextArea(element: HTMLElement): HTMLTextAreaElement {
  if (!(element instanceof HTMLTextAreaElement)) throw new Error("expected a textarea element")
  return element
}

function asButton(element: HTMLElement): HTMLButtonElement {
  if (!(element instanceof HTMLButtonElement)) throw new Error("expected a button element")
  return element
}

/** The text a `PUT` request carried, for a fixture's `RequestInit['body']`, which is not always a string. */
function requestBody(body: BodyInit | null | undefined): string {
  if (typeof body !== "string") throw new Error("expected the request body to be a string")
  return body
}

function isPutRequest(value: unknown): value is { revision: string; content: string } {
  return (
    typeof value === "object" &&
    value !== null &&
    "revision" in value &&
    "content" in value &&
    typeof value.revision === "string" &&
    typeof value.content === "string"
  )
}

function isBodyWithChanges(value: unknown): value is { changes: unknown } {
  return typeof value === "object" && value !== null && "changes" in value
}

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

function colourEntry(key: string, value: string): ConfigEntry {
  return entry({ path: key, key, label: key, value })
}

function ladder(hours: string[], colours: string[]): ConfigEntry[] {
  const keys = ["tier-01", "tier-02", "tier-03"]
  return [
    entry({ path: "admin", key: "admin", label: "admin", value: "#ff5555" }),
    entry({ path: "hours", key: "hours", label: "hours", kind: "MAP", editable: false }),
    ...keys.map((key, at) => entry({ path: `hours.${key}`, key, label: key, type: "INTEGER", value: hours[at] })),
    entry({ path: "colours", key: "colours", label: "colours", kind: "MAP", editable: false }),
    ...keys.map((key, at) => entry({ path: `colours.${key}`, key, label: key, value: colours[at] })),
  ]
}

/** What is on the screen: everything `sr-only` is for a screen reader and not for the eye. */
function visibleText(element: HTMLElement): string {
  const copy = element.cloneNode(true)
  if (!(copy instanceof HTMLElement)) throw new Error("expected a cloned element")
  copy.querySelectorAll(".sr-only").forEach((hidden) => hidden.remove())
  return copy.textContent ?? ""
}

/**
 * By id, not by value: a colour field is an `<input type="color">` and a text box carrying the
 * same value, so `getByDisplayValue` is ambiguous for every colour on the page. The id is the
 * entry's own path.
 */
function fieldFor(container: HTMLElement, path: string): HTMLInputElement {
  return asInput(nonNull(container.querySelector<HTMLElement>(`[id="${path}"]`), `a field drawn for ${path}`))
}

function nonNull<T>(value: T | null, what: string): T {
  if (value === null) throw new Error(`expected ${what}`)
  return value
}

const GUILD_UNAVAILABLE = { available: false, reason: "no bot token in this test", entries: [] }

/** One `/api/config/<path>` answer per fixture file, keyed exactly the way the route is called. */
function backend(documents: Record<string, ConfigLocation & Record<string, unknown>>) {
  const listing = Object.values(documents).map(({ service, name, path, readable, writable }) => ({
    service,
    name,
    path,
    readable,
    writable,
  }))
  return vi.fn<(url: string) => Promise<Response>>(async (url: string) => {
    if (url === "/api/config") return json(listing)
    if (url === "/api/messages") return json([])
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
  /**
   * The same provider the Shell puts around everything (recreate.test.tsx's own comment on this):
   * EnvironmentOverriddenBadge is a Radix tooltip and throws without one, which would be a test
   * failing for a reason the component does not have.
   */
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

/** Opens a file row by the plain-text name the row shows, not the raw filename. */
async function open(humanName: string) {
  fireEvent.click(await screen.findByText(humanName))
}

/** The tab as the service page draws it, with `?file=` kept in state instead of the URL. */
function Settings({ service }: { service: string }) {
  const [file, setFile] = useState<string | undefined>()
  return <ServiceSettings service={service} file={file} onFile={setFile} />
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

describe("the file row", () => {
  it("shows the plain-text file name and no second line with the path", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "steward-worker/steward.yml": {
          ...location({ path: "steward-worker/steward.yml", name: "steward.yml" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [],
        },
      }),
    )

    draw(<Settings service="steward-worker" />)

    await screen.findByText("Steward")
    /**
     * The path used to sit under the name in monospace and made every row in the list two lines
     * tall, so the assertion is the absence of it.
     */
    expect(screen.queryByText("steward.yml")).toBeNull()
  })

  it("lists Nordtal's translations, then Nordtal's configs, then everything third-party", async () => {
    const files = [
      {
        service: "smp",
        name: "bStats/config.yml",
        path: "smp/bStats/config.yml",
        readable: true,
        writable: true,
        origin: "third-party",
        plugin: "bStats",
      },
      {
        service: "smp",
        name: "smp/milestones.yml",
        path: "smp/smp/milestones.yml",
        readable: true,
        writable: true,
        origin: "nordtal",
        plugin: "SMP",
      },
      {
        service: "smp",
        name: "voicechat/voicechat-server.properties",
        path: "smp/voicechat/voicechat-server.properties",
        readable: true,
        writable: true,
        origin: "third-party",
        plugin: "voicechat",
      },
      {
        service: "smp",
        name: "DisplayTags/config.yml",
        path: "smp/DisplayTags/config.yml",
        readable: true,
        writable: true,
        origin: "nordtal",
        plugin: "Display Tags",
      },
      {
        service: "smp",
        name: "smp/config.yml",
        path: "smp/smp/config.yml",
        readable: true,
        writable: true,
        origin: "nordtal",
        plugin: "SMP",
      },
    ]
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url === "/api/config") return json(files)
        if (url === "/api/messages")
          return json([{ service: "smp", module: "smp", path: "smp/smp/messages", writable: true }])
        throw new Error(`the list asked for ${url}, which this test did not expect`)
      }),
    )

    draw(<Settings service="smp" />)

    await screen.findByText("SMP Translations")
    const nav = screen.getByRole("navigation", { name: "Files" })
    const lines = Array.from(nav.querySelectorAll("h3, button")).map((node) => node.textContent?.trim())
    expect(lines).toEqual([
      "Nordtal",
      "SMP Translations",
      "Config",
      "Display Tags Config",
      "Milestones",
      "Third-party",
      "bStats Config",
      "Voicechat Server",
    ])
  })

  it("draws no group headings when every file is Nordtal's", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "discord-bot/bot.yml": {
          ...location({ path: "discord-bot/bot.yml", name: "bot.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await screen.findByText("Bot")
    expect(screen.getByRole("navigation", { name: "Files" }).querySelector("h3")).toBeNull()
  })
})

/**
 * The search box of an open file: label, key path, current value and explanation text, a secret's
 * value excluded from all of it, and the tree cut down to what matched.
 */
describe("searching a file", () => {
  const file = "steward-worker/steward.yml"

  function withEntries(entries: ConfigEntry[]) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries,
        },
      }),
    )
  }

  const twoFields = [
    entry({ path: "worker.base-url", key: "base-url", label: "Base url", value: "http://steward-worker:8081" }),
    entry({
      path: "limits.max-attempts",
      key: "max-attempts",
      label: "Max attempts",
      explanation: "How many times a failed job is retried before it is given up on.",
    }),
  ]

  it("finds a setting by its label and leaves the others out", async () => {
    withEntries(twoFields)
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await search("base url")

    await screen.findByText("Base url")
    expect(screen.queryByText("Max attempts")).toBeNull()
  })

  it("finds a setting by its current value", async () => {
    withEntries(twoFields)
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await search("8081")

    await screen.findByText("Base url")
    expect(screen.queryByText("Max attempts")).toBeNull()
  })

  it("finds a setting by its explanation text", async () => {
    withEntries(twoFields)
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await search("given up")

    await screen.findByText("Max attempts")
    expect(screen.queryByText("Base url")).toBeNull()
  })

  it("never finds a secret by its value", async () => {
    const token = "MTA1NzE4.super-secret-discord-token"
    withEntries([
      /**
       * As if a future bug sent a value for a secret anyway - the wire contract in lib/api.ts says
       * this never happens, and the search has to refuse it on its own regardless.
       */
      entry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: token }),
    ])
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await search(token)

    await screen.findByText("No match.")
    expect(screen.queryByText("Bot token")).toBeNull()
  })

  it("still finds that secret entry by its label - only the value is excluded", async () => {
    withEntries([
      entry({ path: "discord.bot-token", key: "bot-token", label: "Bot token", secret: true, value: "irrelevant" }),
    ])
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await search("bot token")

    expect(await screen.findByText("Bot token")).not.toBeNull()
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
          restartRequired: false,
          entries: [
            entry({ path: "worker", key: "worker", label: "Worker", kind: "MAP" }),
            entry({ path: "worker.limits", key: "limits", label: "Limits", kind: "MAP" }),
            /**
             * Three levels deep: a heading stops here and the leaf's own path carries the rest
             * instead of a fourth grouping concept.
             */
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

  it("folds a chain of single-child sections into one row that names all of them", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    const row = await screen.findByRole("button", { name: /Worker.*Limits.*Retry/ })
    expect(row.getAttribute("aria-expanded")).toBe("true")
    screen.getByText("Max attempts")
  })

  it("shows no key paths, only names", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")
    await screen.findByText("Max attempts")

    expect(screen.queryByText("worker.limits.retry.max-attempts")).toBeNull()
  })

  it("closes a section on a click, and the fields in it go", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    fireEvent.click(await screen.findByRole("button", { name: /Worker.*Limits.*Retry/ }))

    await waitFor(() => expect(screen.queryByText("Max attempts")).toBeNull())
  })

  it("shows the schema's explanation under a label", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    expect(await screen.findByText("How many times a failed job is retried before it is given up on.")).not.toBeNull()
  })

  it("draws literally no text when the schema says no explanation is needed", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")
    await screen.findByText("Max attempts")

    expect(screen.queryByText("An old mechanical comment that must not show through.")).toBeNull()
  })

  it("falls back to the mechanical comment when there is no schema explanation", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    expect(await screen.findByText("Whatever jcore's comment block above this key used to say.")).not.toBeNull()
  })

  it("marks a key the schema does not cover as not in schema", async () => {
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    expect(await screen.findByText("not in schema")).not.toBeNull()
  })
})

/**
 * An environment variable can take a path over from the file - `NORDTAL_ACCESS_LANGUAGES` does
 * exactly that to `access.yml`'s `languages` - and a field under such an override must not draw
 * like any other editable one, since a save there would look like it worked and change nothing the
 * bot would ever read. `environmentOverridden` is absent/`true`/`false` and all three have to draw
 * differently: absent is "this service never said", not "not overridden".
 */
describe("environment overrides", () => {
  const file = "steward-worker/steward.yml"

  function withField(over: Partial<ConfigEntry>) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [
            entry({
              path: "worker.base-url",
              key: "base-url",
              label: "Base url",
              value: "http://steward-worker:8081",
              ...over,
            }),
          ],
        },
      }),
    )
  }

  it("marks a field the environment currently overrides, and leaves it editable", async () => {
    withField({ environmentOverridden: true })
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await screen.findByText("env override")
    const input = asInput(await screen.findByDisplayValue("http://steward-worker:8081"))
    expect(input.disabled).toBe(false)
  })

  it("shows nothing when the environment does not override this field", async () => {
    withField({ environmentOverridden: false })
    draw(<Settings service="steward-worker" />)
    await open("Steward")
    await screen.findByText("Base url")

    expect(screen.queryByText("env override")).toBeNull()
  })

  it("shows nothing when the service never reported which paths the environment overrides - absent is not the same as false", async () => {
    withField({})
    draw(<Settings service="steward-worker" />)
    await open("Steward")
    await screen.findByText("Base url")

    expect(screen.queryByText("env override")).toBeNull()
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
          restartRequired: false,
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
    draw(<Settings service="steward-worker" />)
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
          restartRequired: false,
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
    draw(<Settings service="steward-worker" />)
    await open("Steward")

    await screen.findByRole("combobox")
    const free = asInput(screen.getByLabelText("Free text"))
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
          restartRequired: false,
          entries: [entry({ path: "host", key: "host", label: "Host", value: "postgres" })],
        },
      }),
    )
    draw(<Settings service="steward-worker" />)
    await open("Database")

    await screen.findByText("Read-only.")
    expect(asInput(screen.getByDisplayValue("postgres")).disabled).toBe(true)
  })

  it("is read-only for a plugin too, whose file is not called database.yml on its own", async () => {
    /**
     * `name` is the path under the service directory, so only the three services that keep their
     * file at the top - discord-bot, steward-worker, steward-ui - are called `database.yml`
     * outright. A plugin's is `smp/database.yml`, and an equality check alone would miss it.
     */
    const file = "smp/smp/database.yml"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "smp/database.yml", service: "smp", writable: true }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [entry({ path: "host", key: "host", label: "Host", value: "postgres" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Database")

    await screen.findByText("Read-only.")
    expect(asInput(screen.getByDisplayValue("postgres")).disabled).toBe(true)
  })
})

describe("a file that does not parse as YAML", () => {
  it("is shown as raw text, editable and with a Save button, when the mount allows a write", async () => {
    const file = "steward-worker/README.txt"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "README.txt" }),
          raw: true,
          reason: "line 1: the top of the file must be a set of keys, found a scalar instead",
          content: "Read me.\n",
          revision: "r1",
        },
      }),
    )
    draw(<Settings service="steward-worker" />)
    await open("Readme")

    /**
     * `findByDisplayValue`'s default normalizer trims trailing whitespace, so the trailing newline
     * the fixture's content ends in is not part of what it matches against.
     */
    await screen.findByDisplayValue("Read me.")
    const save = asButton(screen.getByRole("button", { name: /Save/ }))
    expect(save.disabled).toBe(true)
  })

  it("is shown as raw, read-only text with no Save button, when the mount does not allow a write", async () => {
    const file = "smp/README.txt"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "README.txt", service: "smp", writable: false }),
          raw: true,
          reason: "line 1: the top of the file must be a set of keys, found a scalar instead",
          content: "Read me.\n",
          revision: "r1",
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Readme")

    await screen.findByDisplayValue("Read me.")
    expect(screen.queryByRole("button", { name: /Save/ })).toBeNull()
  })

  it("saves what was typed on Save, and shows a syntax warning without undoing it", async () => {
    const file = "steward-worker/config.yml"
    const broken = "one: 1\ntwo: [unterminated\n"
    let putBody: { revision: string; content: string } | undefined
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url, init) => {
      if (url === "/api/messages") return json([])
      if (url === "/api/config") {
        return json([{ service: "steward-worker", name: "config.yml", path: file, readable: true, writable: true }])
      }
      if (url === "/api/discord/roles" || url === "/api/discord/channels") return json(GUILD_UNAVAILABLE)
      if (url === `/api/config-raw/${file}` && init?.method === "PUT") {
        const parsed: unknown = JSON.parse(requestBody(init.body))
        if (!isPutRequest(parsed)) throw new Error("the raw save did not carry a revision and content")
        putBody = parsed
        return json({
          ...location({ path: file, name: "config.yml" }),
          raw: true,
          reason: "line 3: expected ',' or ']', but got :",
          content: broken,
          revision: "r2",
          warnings: ["Line 3: not valid YAML: expected ',' or ']', but got :"],
        })
      }
      if (url === `/api/config/${file}`) {
        return json({
          ...location({ path: file, name: "config.yml" }),
          raw: true,
          reason: "line 1: the top of the file must be a set of keys, found a scalar instead",
          content: "one: 1\n",
          revision: "r1",
        })
      }
      throw new Error(`the form asked for ${url}, which this test did not expect`)
    })
    vi.stubGlobal("fetch", fetchMock)

    draw(<Settings service="steward-worker" />)
    await open("Config")

    const editor = asTextArea(await screen.findByLabelText("Raw content of config.yml"))
    fireEvent.change(editor, { target: { value: broken } })
    fireEvent.click(screen.getByRole("button", { name: /Save/ }))

    await screen.findByText("Line 3: not valid YAML: expected ',' or ']', but got :")
    expect(putBody).toEqual({ revision: "r1", content: broken })
    /**
     * The warning is shown beside the save, not instead of it - the text the operator typed is
     * still what is on screen, matching what the fake worker above actually wrote.
     * `getByDisplayValue` collapses inner whitespace under its default normalizer, which would
     * treat this content's own line break as insignificant, so the element's real `value` is
     * asserted directly instead.
     */
    expect(editor.value).toBe(broken)
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
          restartRequired: false,
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
    draw(<Settings service="steward-worker" />)
    await open("Legacy")

    await screen.findByText("Mechanical, no schema wrote this file.")
    expect(screen.queryByText("Shown as raw text.")).toBeNull()
    screen.getByText("Port")
  })
})

/**
 * Repeatable cards, wired through the real form rather than tested in isolation the way
 * `repeatable-cards.test.tsx` does it - these prove `Control` actually reaches for
 * `RepeatableCards` on a `SECTIONS` entry, and that the whole page still only writes on Save.
 *
 * The worker does not send `kind: "SECTIONS"` today - see the comment on `ConfigEntry.kind` in
 * `lib/api.ts` - so every fixture below is this file's own proposal for the shape.
 */
describe("repeatable cards for a SECTIONS entry", () => {
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
          restartRequired: false,
          entries: [languages([section("en", "")])],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await open("Access")

    await screen.findByDisplayValue("en")
    fireEvent.click(screen.getByRole("button", { name: /Add entry/ }))

    const tags = screen.getAllByLabelText("Tag").map(asInput)
    expect(tags.map((input) => input.value)).toEqual(["en", ""])
  })

  it("titles a language card by its name, not its tag", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [languages([section("en", ""), section("de", "")])],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await open("Access")

    await screen.findByDisplayValue("en")

    screen.getByText("English")
    screen.getByText("Deutsch")
    expect(screen.queryByText("Entry 1")).toBeNull()
    expect(screen.queryByText("en", { selector: "span" })).toBeNull()

    /**
     * A freshly added, still-blank card has no tag yet and falls back to the plain index rather
     * than showing an empty title.
     */
    fireEvent.click(screen.getByRole("button", { name: /Add entry/ }))
    screen.getByText("Entry 3")
  })

  it("gives a channel field inside a card the SnowflakePicker, not a plain text box", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [languages([section("en", "")])],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await open("Access")

    /**
     * GUILD_UNAVAILABLE (no bot token in this test) makes the picker degrade to a text input, but
     * it still carries its own fallback hint - a plain ScalarControl text field never shows this.
     */
    expect(await screen.findByText(/Paste the id instead/)).not.toBeNull()
  })

  it("removes a card from the draft only, and writes it on Save - not on the click", async () => {
    let putChanges: unknown
    const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url, init) => {
      if (url === "/api/messages") return json([])
      if (url === "/api/config") {
        return json([{ service: "discord-bot", name: "access.yml", path: file, readable: true, writable: true }])
      }
      if (url === "/api/discord/roles" || url === "/api/discord/channels") return json(GUILD_UNAVAILABLE)
      if (url === `/api/config/${file}` && init?.method === "PUT") {
        const parsed: unknown = JSON.parse(requestBody(init.body))
        if (!isBodyWithChanges(parsed)) throw new Error("the save did not carry changes")
        putChanges = parsed.changes
        return json({
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r2",
          header: [],
          restartRequired: false,
          entries: [languages([section("en", "")])],
        })
      }
      if (url === `/api/config/${file}`) {
        return json({
          ...location({ path: file, name: "access.yml", service: "discord-bot" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [languages([section("en", ""), section("de", "")])],
        })
      }
      throw new Error(`the form asked for ${url}, which this test did not expect`)
    })
    vi.stubGlobal("fetch", fetchMock)

    draw(<Settings service="discord-bot" />)
    await open("Access")
    await screen.findByDisplayValue("de")

    fireEvent.click(screen.getByRole("button", { name: "Remove entry 2" }))
    // Removing asks first - the click only arms the confirmation.
    fireEvent.click(screen.getByRole("button", { name: "Remove it" }))

    // The card is gone from the draft, and the count says so - but nothing has been written yet.
    expect(screen.queryByDisplayValue("de")).toBeNull()
    screen.getByRole("button", { name: "Save 1" })
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false)

    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true))
    expect(putChanges).toEqual({ languages: [{ tag: "en", "contribution-channel": "" }] })
  })
})

/**
 * The five tones of `colours.yml`, end to end through the real form - `colourRuns` and its unit
 * tests in `colour-control.test.tsx` cover the grouping logic in isolation, but this is what a
 * person actually sees on the page: the Settings tab wired up for real, the tree receiving the
 * file's entries, building the run, and the run actually reaching the DOM.
 */
describe("a file of colours, side by side", () => {
  const file = "smp/colours.yml"

  it("draws every tone of one file as one row, not five stacked fields", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "colours.yml", service: "smp" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [
            colourEntry("good", "#8ba888"),
            colourEntry("bad", "#a8888b"),
            colourEntry("warn", "#b08a4a"),
            colourEntry("neutral", "#c9c9c9"),
            colourEntry("muted", "#aaaaaa"),
          ],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Colours")

    const swatches = await screen.findAllByLabelText("Pick a colour")
    expect(swatches).toHaveLength(5)

    /**
     * Every swatch's row ancestor (`.flex-wrap`, the container `EntryList` builds for a run) has to
     * be the very same element - that is what "one row" means in the DOM, as opposed to five
     * separate fields that merely look similar stacked one after another.
     */
    const rows = new Set(swatches.map((swatch) => swatch.closest(".flex-wrap")))
    expect(rows.size).toBe(1)
    expect(rows.has(null)).toBe(false)
  })

  it("still offers the picker for a colour that is not part of any row", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "colours.yml", service: "smp" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          /**
           * Only one colour in the file - `colourRuns` never groups a single entry, but the field
           * itself is still exactly the colour it looks like and still gets the picker.
           */
          entries: [colourEntry("good", "#8ba888"), entry({ path: "label", key: "label", value: "smp" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Colours")

    expect(await screen.findByLabelText("Pick a colour")).not.toBeNull()
  })

  it("leaves an ordinary text field alone - no picker, no swatch", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "colours.yml", service: "smp" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [entry({ path: "label", key: "label", label: "Label", value: "smp" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Colours")

    await screen.findByDisplayValue("smp")
    expect(screen.queryByLabelText("Pick a colour")).toBeNull()
  })
})

/**
 * `prestige.yml`'s two blocks, end to end through the real form.
 *
 * `paired-blocks.test.ts` holds the rule itself; this is the half that rule exists for - one row
 * per tier, with the hour and the colour of that tier in it, drawn by `ServiceConfiguration` from
 * a file that looks exactly like the one the plugin ships.
 */
describe("two blocks that share their keys, as one row per key", () => {
  const file = "smp/prestige.yml"

  function withLadder() {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "prestige.yml", service: "smp" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: ladder(["0", "2", "5"], ["#5fbfae", "#5ea9d6", "#6f93e0"]),
        },
      }),
    )
  }

  it("puts a tier's hour and its colour in the same row", async () => {
    withLadder()
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    const hour = fieldFor(container, "hours.tier-02")
    const colour = fieldFor(container, "colours.tier-02")
    const row = hour.closest("li")

    expect(row).not.toBeNull()
    /**
     * The same `<li>`, which is what "one row per tier" means in the DOM. Two fields that merely
     * look similar, stacked, is precisely the arrangement this ticket exists to end.
     */
    expect(colour.closest("li")).toBe(row)
    expect(row?.textContent).toContain("tier-02")
  })

  it("names the tier once, and each column once for the whole block", async () => {
    withLadder()
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    const row = nonNull(fieldFor(container, "hours.tier-02").closest<HTMLElement>("li"), "the row")
    /**
     * `tier-02` is the row's subject and is said once, on the left. The two halves carry no visible
     * label of their own: three rows repeating "hours" and "colours" is three repetitions of a
     * column heading, and on a phone it is also the width the hex field needs. What a screen reader
     * hears is the next test; this one is about what is drawn, so the `sr-only` labels come out
     * first - `textContent` cannot tell them apart from anything else.
     */
    expect(visibleText(row).match(/tier-02/g) ?? []).toHaveLength(1)
    const block = nonNull(row.closest<HTMLElement>("ul"), "the block")
    expect(visibleText(block).match(/hours/g) ?? []).toHaveLength(1)
    expect(visibleText(block).match(/colours/g) ?? []).toHaveLength(1)
  })

  /**
   * The label is hidden, not deleted. A field whose only name was a column heading three rows above
   * it has no accessible name at all, which is the kind of thing a picture never shows.
   */
  it("keeps each half's label for a screen reader", async () => {
    withLadder()
    draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    expect(screen.getByLabelText("hours tier-02")).toBeTruthy()
    expect(screen.getByLabelText("colours tier-02")).toBeTruthy()
  })

  it("keeps the colour picker, because a paired field is still the field it was", async () => {
    withLadder()
    draw(<Settings service="smp" />)
    await open("Prestige")

    // Three tiers, plus `admin` standing outside the pair as an ordinary colour field.
    expect(await screen.findAllByLabelText("Pick a colour")).toHaveLength(4)
  })

  it("writes both halves of a row under their own real keys", async () => {
    withLadder()
    const fetchMock = vi.mocked(global.fetch)
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    await screen.findByDisplayValue("2")
    fireEvent.change(fieldFor(container, "hours.tier-02"), { target: { value: "3" } })
    fireEvent.change(fieldFor(container, "colours.tier-02"), { target: { value: "#112233" } })
    fireEvent.click(screen.getByRole("button", { name: /save/i }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true))
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT")
    const body = requestBody(put?.[1]?.body)
    const parsed: unknown = JSON.parse(body)
    if (!isBodyWithChanges(parsed)) throw new Error("the save did not carry changes")
    expect(parsed.changes).toEqual({
      /**
       * The display label was overridden, the path was not - which is what keeps a search hit and
       * a save pointing at the same key.
       */
      "hours.tier-02": "3",
      "colours.tier-02": "#112233",
    })
  })

  it("leaves two blocks that do not line up as two ordinary stacks", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "prestige.yml", service: "smp" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [
            entry({ path: "hours", key: "hours", label: "hours", kind: "MAP", editable: false }),
            entry({ path: "hours.tier-01", key: "tier-01", label: "tier-01", value: "0" }),
            entry({ path: "hours.tier-02", key: "tier-02", label: "tier-02", value: "2" }),
            entry({ path: "colours", key: "colours", label: "colours", kind: "MAP", editable: false }),
            entry({ path: "colours.tier-01", key: "tier-01", label: "tier-01", value: "#5fbfae" }),
          ],
        },
      }),
    )
    const { container } = draw(<Settings service="smp" />)
    await open("Prestige")

    /**
     * Nothing is paired, so nothing is a row - and, crucially, nothing has been dropped either:
     * all three values are still on the page, each as its own field.
     */
    await screen.findByDisplayValue("2")
    expect(fieldFor(container, "hours.tier-02").closest("li")).toBeNull()
    expect(fieldFor(container, "hours.tier-01").value).toBe("0")
    expect(fieldFor(container, "colours.tier-01").value).toBe("#5fbfae")
  })
})

/**
 * A click on a search hit has to actually take you there, including the case that looks like the
 * easiest of them: searching while already standing on the service page the hit belongs to.
 *
 * `navigate` to the route you are on is a no-op, nothing remounts, `service` does not change, and
 * the effect that consumes a pending jump is keyed on exactly that - so without care the click does
 * nothing at all, and leaves the jump in the map, where it fires the next time somebody arrives on
 * this page.
 */
describe("a hit that arrives while this page is already open", () => {
  const file = "steward-worker/steward.yml"

  function drawWith(entries: ConfigEntry[]) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward.yml" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries,
        },
      }),
    )
    return draw(<Settings service="steward-worker" />)
  }

  afterEach(() => {
    /**
     * Nothing may survive into the next test: the map is module level and a leftover jump is
     * exactly the second bug this ticket is about.
     */
    takePendingJump("steward-worker")
  })

  it("opens the file and lands on the field, without the page having remounted", async () => {
    drawWith([
      entry({ path: "worker.base-url", key: "base-url", label: "Base url" }),
      entry({ path: "worker.token", key: "token", label: "Token" }),
    ])
    await screen.findByText("Steward")
    // Closed to begin with: the field is in a file nobody has opened.
    expect(screen.queryByText("Base url")).toBeNull()

    setPendingJump("steward-worker", { file, path: "worker.base-url" })

    await waitFor(() => expect(screen.queryByText("Base url")).not.toBeNull())
  })

  it("consumes the jump, so arriving here again does not reopen it", async () => {
    drawWith([entry({ path: "worker.base-url", key: "base-url", label: "Base url" })])
    await screen.findByText("Steward")

    setPendingJump("steward-worker", { file, path: "worker.base-url" })
    await waitFor(() => expect(screen.queryByText("Base url")).not.toBeNull())

    expect(takePendingJump("steward-worker")).toBeUndefined()
  })

  it("lands again on a second, identical hit while still standing on the field", async () => {
    drawWith([entry({ path: "worker.base-url", key: "base-url", label: "Base url" })])
    await screen.findByText("Steward")
    const scroll = vi.spyOn(Element.prototype, "scrollIntoView")
    try {
      setPendingJump("steward-worker", { file, path: "worker.base-url" })
      await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1), { timeout: 1500 })

      setPendingJump("steward-worker", { file, path: "worker.base-url" })
      await waitFor(() => expect(scroll).toHaveBeenCalledTimes(2), { timeout: 1500 })
    } finally {
      scroll.mockRestore()
    }
  })

  it("ignores a jump meant for another service", async () => {
    drawWith([entry({ path: "worker.base-url", key: "base-url", label: "Base url" })])
    await screen.findByText("Steward")

    setPendingJump("smp", { file: "smp/steward.yml", path: "grave.decay.enabled" })

    // Still shut, and the other service's jump is still there for the page it was meant for.
    await waitFor(() => expect(screen.queryByText("Base url")).toBeNull())
    expect(takePendingJump("smp")).toEqual({
      file: "smp/steward.yml",
      path: "grave.decay.enabled",
    })
  })
})

describe("the arrow back to the top", () => {
  it("shows only once the top of the file has scrolled out of view", async () => {
    let report: ((entries: Array<{ isIntersecting: boolean }>) => void) | null = null
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) {
          report = callback
        }
        observe() {
          report?.([{ isIntersecting: true }])
        }
        disconnect() {}
        unobserve() {}
      },
    )
    vi.stubGlobal(
      "fetch",
      backend({
        "steward-worker/steward.yml": {
          ...location({ path: "steward-worker/steward.yml", name: "steward.yml" }),
          revision: "r1",
          header: [],
          restartRequired: false,
          entries: [entry({ path: "port", key: "port", value: "8080" })],
        },
      }),
    )
    draw(<Settings service="steward-worker" />)
    await open("Steward")
    await screen.findByRole("searchbox", { name: "Search this file" })

    // Three fields and nothing scrolls: an arrow here would do nothing.
    expect(screen.queryByRole("button", { name: "Back to the top" })).toBeNull()

    act(() => report?.([{ isIntersecting: false }]))
    expect(screen.getByRole("button", { name: "Back to the top" })).toBeTruthy()
  })
})
