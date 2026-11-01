import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { AnnouncementView, ConfigDocument, ConfigEntry, Document, DocumentView } from "@/lib/api"
import { matches, titleOf } from "@/lib/announcements"
import { urlOf, wideScreen } from "@/lib/query-fixtures"
import { asButton } from "@/lib/test-elements"
import { Announcements } from "@/components/steward/announcements"
import { TooltipProvider } from "@/components/ui/tooltip"

function json(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function text(en: string, de: string, style?: "TITLE"): Document {
  return { parts: [{ type: "TEXT", lines: [{ text: { en, de }, ...(style ? { style } : {}) }] }] }
}

const TEMPLATE_DOCUMENT = text("Announcement", "Announcement DE", "TITLE")

const TEMPLATE: DocumentView = {
  declaration: {
    namespace: "discord",
    name: "announcement",
    title: "New announcement",
    englishOnly: false,
    area: "Announcements",
    shape: "MESSAGE",
    rows: 0,
    values: {},
    slots: [],
  },
  named: {},
  services: ["discord-bot"],
  game: false,
  packaged: TEMPLATE_DOCUMENT,
  hash: "h",
  outdated: false,
  adapted: [],
}

function field(key: string, value: string): ConfigEntry {
  return {
    path: key,
    key,
    label: key,
    value,
    kind: "SCALAR",
    type: "STRING",
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    editable: true,
    secret: false,
    environmentOverridden: false,
  }
}

const ACCESS: ConfigDocument = {
  service: "discord-bot",
  path: "discord-bot/access",
  name: "access",
  label: "",
  live: true,
  readable: true,
  writable: true,
  revision: "r1",
  entries: [
    {
      ...field("languages", ""),
      kind: "SECTIONS",
      editable: false,
      sections: [
        [field("tag", "en"), field("announcement-channel", "222")],
        [field("tag", "de"), field("announcement-channel", "111")],
      ],
    },
  ],
}

function draft(id: number, document: Document, channels: Record<string, string>): AnnouncementView {
  return {
    id,
    document,
    changed: "2026-11-01T10:00:00Z",
    targets: Object.entries(channels).map(([language, channel]) => ({ language, channel, delivery: "UNSENT" })),
  }
}

function sent(id: number, document: Document, notPosted: string[] = []): AnnouncementView {
  return {
    id,
    document,
    changed: "2026-10-01T10:00:00Z",
    sent: "2026-10-01T10:05:00Z",
    targets: ["de", "en"].map((language) => ({
      language,
      channel: language === "de" ? "111" : "222",
      delivery: notPosted.includes(language) ? "NOT_POSTED" : "POSTED",
    })),
  }
}

type Call = { method: string; url: string; body: unknown }

function backend(list: { drafts: AnnouncementView[]; sent: AnnouncementView[] }) {
  const calls: Call[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: { method?: string; body?: string }) => {
      const url = urlOf(input)
      const method = init?.method ?? "GET"
      if (method !== "GET" && url !== "/api/documents/render")
        calls.push({ method, url, body: init?.body ? JSON.parse(init.body) : undefined })
      if (url === "/api/announcements" && method === "GET") return json(200, list)
      if (url === "/api/announcements" && method === "POST") return json(201, { id: 9 })
      if (/^\/api\/announcements\/\d+$/.test(url)) return json(204, undefined)
      if (/^\/api\/announcements\/\d+\/send$/.test(url)) return json(202, { ids: { de: "announce:5:de" } })
      if (url.startsWith("/api/commands/")) return json(200, { id: "announce:5:de", status: "DONE" })
      if (url === "/api/documents")
        return json(200, { documents: [TEMPLATE], languages: ["en", "de"], sharedParts: [] })
      if (url === "/api/documents/render") return json(200, { components: 1, most: 40, problems: [] })
      if (url === "/api/setting-groups/discord-bot/access") return json(200, ACCESS)
      if (url === "/api/discord/channels") {
        return json(200, {
          available: true,
          entries: [
            { id: "111", name: "announcements-de", type: 0 },
            { id: "222", name: "announcements", type: 0 },
          ],
        })
      }
      if (url === "/api/game-data") return json(404, {})
      throw new Error(`the page asked for ${method} ${url}, which this test did not expect`)
    }),
  )
  return calls
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => wideScreen())

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("Announcements", () => {
  it("starts a new draft from the bot's document with the default channel of each language", async () => {
    const calls = backend({ drafts: [], sent: [] })
    draw(<Announcements />)

    expect(await screen.findByText("No announcements yet")).toBeTruthy()
    const compose = screen.getByRole("button", { name: "New announcement" })
    await waitFor(() => expect(asButton(compose).disabled).toBe(false))
    fireEvent.click(compose)

    await waitFor(() =>
      expect(calls).toEqual([
        {
          method: "POST",
          url: "/api/announcements",
          body: { document: TEMPLATE_DOCUMENT, channels: { en: "222", de: "111" } },
        },
      ]),
    )
  })

  it("saves a draft with a channel removed and sends it to the one channel left", async () => {
    const calls = backend({ drafts: [draft(4, text("Tonight", "Tonight DE"), { de: "111", en: "222" })], sent: [] })
    draw(<Announcements />)

    fireEvent.click(await screen.findByRole("button", { name: /Tonight/ }))
    const article = await screen.findByRole("article", { name: "Tonight" })
    expect(within(article).getByText("English channel")).toBeTruthy()
    expect(within(article).getByText("Deutsch channel")).toBeTruthy()

    fireEvent.click(await within(article).findByRole("button", { name: "Deutsch channel" }))
    fireEvent.click(await screen.findByRole("option", { name: "none" }))
    expect(await within(article).findByText("Not sent in this language")).toBeTruthy()

    fireEvent.click(within(article).getByRole("button", { name: /Send to Discord/ }))
    const dialog = await screen.findByRole("alertdialog")
    expect(within(dialog).getByText(/in #announcements\./)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))

    await waitFor(() =>
      expect(calls).toEqual([
        {
          method: "PUT",
          url: "/api/announcements/4",
          body: { document: text("Tonight", "Tonight DE"), channels: { en: "222" } },
        },
        { method: "POST", url: "/api/announcements/4/send", body: undefined },
      ]),
    )
  })

  it("searches the sent ones, opens one to read and retries only what was not posted", async () => {
    const calls = backend({
      drafts: [],
      sent: [sent(2, text("The end opens", "The end DE"), ["de"]), sent(1, text("Maintenance", "Maintenance DE"))],
    })
    draw(<Announcements />)

    const archive = await screen.findByRole("button", { name: /Sent/ })
    expect(within(archive).getByText("1 not posted everywhere")).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Maintenance/ })).toBeNull()
    fireEvent.click(archive)
    fireEvent.change(screen.getByRole("searchbox", { name: "Search sent announcements" }), {
      target: { value: "end de" },
    })
    expect(screen.queryByRole("button", { name: /Maintenance/ })).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: /The end opens/ }))

    const article = await screen.findByRole("article", { name: "The end opens" })
    expect(within(article).getByText("Not posted in Deutsch")).toBeTruthy()
    expect(within(article).getByText("Posted in English")).toBeTruthy()
    expect(within(article).queryByText("Send to Discord")).toBeNull()
    fireEvent.click(asButton(within(article).getByRole("button", { name: /Post in Deutsch/ })))

    await waitFor(() => expect(calls).toEqual([{ method: "POST", url: "/api/announcements/2/send", body: undefined }]))
  })
})

describe("titleOf and matches", () => {
  it("take the first line without its marks and search every language", () => {
    expect(titleOf(text("**Tonight**", "Tonight DE"), "de")).toBe("Tonight DE")
    expect(titleOf(text("**Tonight**", "Tonight DE"), "en")).toBe("Tonight")
    expect(titleOf({ parts: [] }, "en")).toBe("")
    expect(matches(text("Tonight", "Tonight DE"), "NIGHT D")).toBe(true)
    expect(matches(text("Tonight", "Tonight DE"), "tomorrow")).toBe(false)
    expect(matches(text("Tonight", "Tonight DE"), " ")).toBe(true)
  })
})
