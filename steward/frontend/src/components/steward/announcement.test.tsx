import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigEntry, ConfigDocument } from "@/lib/api"
import { announcementTargets } from "@/lib/announcement-targets"
import { AnnouncementForm } from "@/components/steward/announcement"
import { TooltipProvider } from "@/components/ui/tooltip"
import { asButton, asTextArea } from "@/lib/test-elements"
import { words } from "@/lib/query-fixtures"

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
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

function accessFile({ en = "111", de = "", overridden = false } = {}): ConfigDocument {
  return {
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
        path: "languages",
        key: "languages",
        label: "languages",
        kind: "SECTIONS",
        type: "STRING",
        explanation: "",
        noExplanationNeeded: false,
        filled: true,
        editable: false,
        secret: false,
        environmentOverridden: overridden,
        sections: [
          [field("tag", "en"), field("announcement-channel", en)],
          [field("tag", "de"), field("announcement-channel", de)],
        ],
      },
    ],
  }
}

function backend(
  file: unknown = accessFile(),
  runs: Record<string, Record<string, unknown>> = {},
  open: { language: string; text: string; posted: boolean }[] = [],
) {
  const sent: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
      if (init?.method === "POST" && url === "/api/announcements") {
        const body: { texts: Record<string, string> } = JSON.parse(init.body ?? "")
        sent.push(body)
        return json(202, { ids: Object.fromEntries(Object.keys(body.texts).map((tag) => [tag, `announce:2:${tag}`])) })
      }
      if (url === "/api/announcements/open") return json(200, { lines: open })
      if (url === "/api/setting-groups/discord-bot/access") return json(200, file)
      if (url === "/api/discord/channels") {
        return json(200, { available: true, entries: [{ id: "111", name: "announcements", type: 0 }] })
      }
      if (url.startsWith("/api/commands/")) {
        const id = decodeURIComponent(url.split("/").pop() ?? "")
        return json(200, { id, status: "DONE", ...runs[id.split(":")[2]] })
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

function send() {
  return asButton(screen.getByRole("button", { name: "Send" }))
}

describe("AnnouncementForm", () => {
  it("sends nothing until every language has its text, then one text per language", async () => {
    const sent = backend()
    draw(<AnnouncementForm />)

    const english = await screen.findByLabelText("English")
    const german = screen.getByLabelText("Deutsch")

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
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    expect(within(send()).getByRole("status").textContent).toBe("Done")
    expect(screen.queryByText("Posted.")).toBeNull()

    fireEvent.change(english, { target: { value: "The end opens at nine." } })
    expect(within(send()).getByRole("status").textContent).toBe("")
    expect(screen.queryByText("Posted in Deutsch")).toBeNull()
    expect(screen.queryByText("Not posted in English")).toBeNull()
  })

  it("names the reason of a language the bot did not post, and keeps the texts for another try", async () => {
    backend(accessFile(), { de: { status: "FAILED", result: words("Not posted. No German channel.") } })
    draw(<AnnouncementForm />)

    fireEvent.change(await screen.findByLabelText("English"), { target: { value: "Tonight." } })
    fireEvent.change(screen.getByLabelText("Deutsch"), { target: { value: "Heute Abend." } })
    fireEvent.click(send())
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))

    expect((await within(dialog).findByRole("alert")).textContent).toContain("Not posted. No German channel.")
    expect(asTextArea(screen.getByLabelText("English")).value).toBe("Tonight.")
  })

  it("names the languages that went out, and sends only the others again", async () => {
    const sent = backend(accessFile(), { de: { status: "FAILED", result: words("Not posted. No German channel.") } })
    draw(<AnnouncementForm />)

    fireEvent.change(await screen.findByLabelText("English"), { target: { value: "Tonight." } })
    fireEvent.change(screen.getByLabelText("Deutsch"), { target: { value: "Heute Abend." } })
    fireEvent.click(send())
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))

    expect(await within(dialog).findByText("Posted in English")).toBeTruthy()
    expect(within(dialog).getByText("Not posted in Deutsch")).toBeTruthy()
    expect(within(dialog).getByText("It is posted in Discord at once.")).toBeTruthy()

    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toEqual({ texts: { de: "Heute Abend." } })
  })

  it("keeps naming the languages that went out on the card once the dialog is cancelled", async () => {
    backend(accessFile(), { de: { status: "FAILED", result: words("Not posted. No German channel.") } })
    draw(<AnnouncementForm />)

    fireEvent.change(await screen.findByLabelText("English"), { target: { value: "Tonight." } })
    fireEvent.change(screen.getByLabelText("Deutsch"), { target: { value: "Heute Abend." } })
    fireEvent.click(send())
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))
    await within(dialog).findByText("Posted in English")
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    expect(screen.getByText("Posted in English")).toBeTruthy()
    expect(screen.getByText("Not posted in Deutsch")).toBeTruthy()
    expect(screen.getByRole("alert").textContent).toContain("Not posted. No German channel.")
  })

  it("after a reload, brings back an announcement that failed for some languages and sends only those", async () => {
    const sent = backend(accessFile(), {}, [
      { language: "en", text: "Tonight.", posted: true },
      { language: "de", text: "Heute Abend.", posted: false },
    ])
    draw(<AnnouncementForm />)

    await waitFor(() => expect(asTextArea(screen.getByLabelText("English")).value).toBe("Tonight."))
    expect(asTextArea(screen.getByLabelText("Deutsch")).value).toBe("Heute Abend.")
    expect(screen.getByText("Posted in English")).toBeTruthy()
    expect(screen.getByText("Not posted in Deutsch")).toBeTruthy()

    fireEvent.click(send())
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Send" }))
    await waitFor(() => expect(sent).toEqual([{ texts: { de: "Heute Abend." } }]))
  })

  it("names the channel each language lands in, and says when one has none", async () => {
    backend()
    draw(<AnnouncementForm />)

    expect(await screen.findByText("announcements")).toBeTruthy()
    expect(screen.getByText("no channel")).toBeTruthy()
  })

  it("does not name the file's channels when the host environment sets them", async () => {
    backend(accessFile({ overridden: true }))
    draw(<AnnouncementForm />)

    expect(await screen.findAllByText("channel set by the host")).toHaveLength(2)
    expect(screen.queryByText("no channel")).toBeNull()
  })
})

describe("announcementTargets", () => {
  it("reads each language's channel in file order", () => {
    expect(announcementTargets(accessFile())).toEqual({
      languages: [
        { tag: "en", channel: "111" },
        { tag: "de", channel: "" },
      ],
      overridden: false,
    })
  })
})
