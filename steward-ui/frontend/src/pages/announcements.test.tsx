import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigDocument } from "@/lib/api"
import { announcementTargets } from "@/lib/announcement-targets"
import { AnnouncementsPage, senderName } from "@/pages/announcements"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * One form writes every language, nothing leaves until every language has its text, and the page
 * says where each line lands - next to what was announced lately, the SMP's lines included.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function field(key: string, value: string) {
  return { path: key, key, label: key, value, kind: "SCALAR" }
}

function accessFile({ en = "111", de = "", overridden = false } = {}) {
  return {
    path: "discord-bot/access.yml",
    name: "access.yml",
    revision: "r1",
    header: [],
    entries: [
      {
        path: "languages",
        key: "languages",
        kind: "SECTIONS",
        environmentOverridden: overridden,
        sections: [
          [field("tag", "en"), field("announcement-channel", en)],
          [field("tag", "de"), field("announcement-channel", de)],
        ],
      },
    ],
  }
}

const RECENT = {
  recent: [
    {
      id: "9",
      language: "de",
      text: "The second language, lately.",
      source: "WEB",
      requestedBy: "Some Admin (123456)",
      requested: new Date().toISOString(),
      status: "DONE",
      result: "Posted.",
    },
    {
      id: "8",
      language: "en",
      text: "The end is open.",
      source: "CONSOLE",
      requestedBy: "smp",
      requested: new Date().toISOString(),
      status: "EXPIRED",
    },
  ],
}

function backend(file: unknown = accessFile()) {
  const sent: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST" && url === "/api/announcements") {
        sent.push(JSON.parse(String(init.body)))
        return json(202, { ids: { en: "21", de: "22" } })
      }
      if (url === "/api/announcements") return json(200, RECENT)
      if (url === "/api/config/discord-bot/access.yml") return json(200, file)
      if (url === "/api/discord/channels") {
        return json(200, { available: true, entries: [{ id: "111", name: "announcements", type: 0 }] })
      }
      if (url.startsWith("/api/commands/")) {
        return json(200, { id: url.split("/").pop(), status: "DONE", result: "Posted." })
      }
      throw new Error(`the page asked for ${url}, which this test did not expect`)
    }),
  )
  return sent
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AnnouncementsPage", () => {
  it("sends nothing until every language has its text, then one text per language", async () => {
    const sent = backend()
    draw(<AnnouncementsPage />)

    const english = await screen.findByLabelText("English")
    const german = screen.getByLabelText("Deutsch")
    const send = () => screen.getByRole("button", { name: "Send" }) as HTMLButtonElement

    fireEvent.change(english, { target: { value: "The end opens tonight." } })
    expect(send().disabled).toBe(true)
    fireEvent.change(german, { target: { value: "  " } })
    expect(send().disabled).toBe(true)
    fireEvent.change(german, { target: { value: " The second language, tonight. " } })
    expect(send().disabled).toBe(false)

    fireEvent.click(send())
    const dialog = await screen.findByRole("alertdialog")
    expect(sent).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))

    await waitFor(() =>
      expect(sent).toEqual([{ texts: { en: "The end opens tonight.", de: "The second language, tonight." } }]),
    )
    // Twice for the two rows just sent, once in the list of recent ones.
    await waitFor(() => expect(screen.getAllByText("Posted.")).toHaveLength(3))
    expect((screen.getByLabelText("English") as HTMLTextAreaElement).value).toBe("")
  })

  it("names the channel each language lands in, and says when one has none", async () => {
    backend()
    draw(<AnnouncementsPage />)

    expect(await screen.findByText("announcements")).toBeTruthy()
    expect(screen.getByText("no channel")).toBeTruthy()
  })

  it("does not name the file's channels when the host environment sets them", async () => {
    backend(accessFile({ overridden: true }))
    draw(<AnnouncementsPage />)

    expect(await screen.findAllByText("channel set by the host")).toHaveLength(2)
    expect(screen.queryByText("no channel")).toBeNull()
  })

  it("lists the latest lines of both senders, with what became of them", async () => {
    backend()
    draw(<AnnouncementsPage />)

    expect(await screen.findByText("The second language, lately.")).toBeTruthy()
    expect(screen.getByText("Some Admin")).toBeTruthy()
    expect(screen.getByText("smp")).toBeTruthy()
    expect(screen.getByText("expired")).toBeTruthy()
  })
})

describe("announcementTargets", () => {
  it("has nothing to say about a raw file", () => {
    expect(
      announcementTargets({ raw: true, content: "", path: "x", name: "x" } as unknown as ConfigDocument),
    ).toBeNull()
  })

  it("reads each language's channel in file order", () => {
    expect(announcementTargets(accessFile() as unknown as ConfigDocument)).toEqual({
      languages: [
        { tag: "en", channel: "111" },
        { tag: "de", channel: "" },
      ],
      overridden: false,
    })
  })
})

describe("senderName", () => {
  it("drops the Discord id a web row carries", () => {
    expect(senderName("Some Admin (123456)")).toBe("Some Admin")
    expect(senderName("smp")).toBe("smp")
  })
})
