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

/** One form writes every language, nothing is sent until each has its text, and each line's destination is shown. */

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
    restartRequired: false,
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

function backend(file: unknown = accessFile()) {
  const sent: unknown[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
      if (init?.method === "POST" && url === "/api/announcements") {
        sent.push(JSON.parse(init.body ?? ""))
        return json(202, { ids: { en: "21", de: "22" } })
      }
      if (url === "/api/setting-groups/discord-bot/access") return json(200, file)
      if (url === "/api/discord/channels") {
        return json(200, { available: true, entries: [{ id: "111", name: "announcements", type: 0 }] })
      }
      if (url.startsWith("/api/commands/")) {
        return json(200, { id: url.split("/").pop(), status: "DONE", result: words("Posted.") })
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

/** The Send button, narrowed to the type its `disabled` assertions need. */
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
    // Once for each row just sent.
    await waitFor(() => expect(screen.getAllByText("Posted.")).toHaveLength(2))
    expect(asTextArea(screen.getByLabelText("English")).value).toBe("")
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
