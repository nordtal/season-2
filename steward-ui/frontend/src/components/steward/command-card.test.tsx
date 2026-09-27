import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { AdminCommand } from "@/lib/api"
import { CommandCard, accountOptions } from "@/components/steward/command-card"
import { TooltipProvider } from "@/components/ui/tooltip"
import { asButton, asElement } from "@/lib/test-elements"

/** A refusal shows wherever the operator is looking: in the open dialog, else in the row, never both. */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const SAFE: AdminCommand = {
  name: "season phase",
  path: ["season", "phase"],
  target: "SMP",
  adminOnly: true,
  irreversible: false,
  arguments: [],
}

const IRREVERSIBLE: AdminCommand = {
  name: "milestone unlock",
  path: ["milestone", "unlock"],
  target: "SMP",
  adminOnly: true,
  irreversible: true,
  arguments: [],
}

/** The three routes the card uses; `/api/commands/{id}` is the poll and `/api/commands` the request. */
function backend(
  over: {
    commands?: AdminCommand[]
    ask?: () => { status: number; body: unknown }
    run?: () => { status: number; body: unknown }
  } = {},
) {
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url: string, init?: RequestInit) => {
    if (url === "/api/commands" && init?.method === "POST") {
      const answer = over.ask?.() ?? { status: 202, body: { id: "r1", status: "PENDING" } }
      return json(answer.status, answer.body)
    }
    if (url === "/api/commands") return json(200, over.commands ?? [SAFE, IRREVERSIBLE])
    if (url.startsWith("/api/commands/")) {
      const answer = over.run?.() ?? { status: 200, body: { id: "r1", status: "PENDING" } }
      return json(answer.status, answer.body)
    }
    throw new Error(`the card asked for ${url}, which this test did not expect`)
  })
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

async function row(name: string): Promise<HTMLElement> {
  const label = await screen.findByText(name)
  const found = label.closest("div.rounded-md")
  if (!found) throw new Error(`the row of ${name} is not shaped the way this test assumed`)
  return asElement(found)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("CommandCard - a command that refuses to be written", () => {
  it("shows the refusal inside the confirmation, where the operator is looking", async () => {
    /** The dialog covers the card, so a `Failure` in the row behind it would go unread. */
    vi.stubGlobal("fetch", backend({ ask: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<CommandCard />)

    fireEvent.click(within(await row("milestone unlock")).getByRole("button", { name: /Run/ }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(within(dialog).queryByRole("alert")).not.toBeNull())
    expect(within(dialog).getByRole("alert").textContent).toContain("The database is not answering.")
    /** `hidden: true` counts the copy behind the overlay, which Radix marks `aria-hidden`. */
    expect(screen.getAllByRole("alert", { hidden: true })).toHaveLength(1)
  })

  it("leaves the confirmation open, because the command has not been written", async () => {
    /** The dialog stays open on failure, since closing it would look like success. */
    vi.stubGlobal("fetch", backend({ ask: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<CommandCard />)

    fireEvent.click(within(await row("milestone unlock")).getByRole("button", { name: /Run/ }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(within(dialog).queryByRole("alert")).not.toBeNull())
    expect(screen.queryByRole("alertdialog")).not.toBeNull()
    expect(within(dialog).getByRole("button", { name: /Run/ })).toBeTruthy()
  })

  it("moves the refusal into the row once the confirmation is gone", async () => {
    /** With nothing covering the card, the refusal belongs next to its button. */
    vi.stubGlobal("fetch", backend({ ask: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<CommandCard />)

    const irreversible = await row("milestone unlock")
    fireEvent.click(within(irreversible).getByRole("button", { name: /Run/ }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: /Run/ }))
    await waitFor(() => expect(within(dialog).queryByRole("alert")).not.toBeNull())

    fireEvent.click(within(dialog).getByRole("button", { name: /Cancel/ }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    expect(within(irreversible).getByRole("alert").textContent).toContain("The database is not answering.")
    expect(screen.getAllByRole("alert")).toHaveLength(1)
  })

  it("shows it in the row straight away for a command that asks nothing first", async () => {
    vi.stubGlobal("fetch", backend({ ask: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<CommandCard />)

    const safe = await row("season phase")
    fireEvent.click(within(safe).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(within(safe).queryByRole("alert")).not.toBeNull())
    expect(screen.queryByRole("alertdialog")).toBeNull()
    expect(within(safe).getByRole("alert").textContent).toContain("The database is not answering.")
  })
})

describe("CommandCard - what became of a row that was written", () => {
  it("says that nothing claimed the row, which is not the same as having failed", async () => {
    /** EXPIRED means the owning service is not listening, a different errand from FAILED. */
    vi.stubGlobal(
      "fetch",
      backend({
        ask: () => ({ status: 202, body: { id: "r1", status: "PENDING" } }),
        run: () => ({ status: 200, body: { id: "r1", status: "EXPIRED" } }),
      }),
    )
    draw(<CommandCard />)

    const safe = await row("season phase")
    fireEvent.click(within(safe).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(safe.textContent).toContain("Nobody picked the row up"))
    expect(safe.textContent).toContain("is not listening")
  })

  it("shows the failure and a way back when the poll itself cannot be answered", async () => {
    /** The row exists and the command may be running; only the asking failed. */
    vi.stubGlobal(
      "fetch",
      backend({
        ask: () => ({ status: 202, body: { id: "r1", status: "PENDING" } }),
        run: () => ({ status: 502, body: { error: "steward-worker is not answering.", where: "steward-worker" } }),
      }),
    )
    draw(<CommandCard />)

    const safe = await row("season phase")
    fireEvent.click(within(safe).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(within(safe).queryByRole("alert")).not.toBeNull())
    expect(within(safe).getByRole("alert").textContent).toContain("steward-worker is not answering.")
    expect(within(safe).queryByText("Being carried out.")).toBeNull()
    expect(within(safe).getByRole("button", { name: /Try again/ })).toBeTruthy()
  })

  it("carries the command's own result text through rather than paraphrasing it", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        ask: () => ({ status: 202, body: { id: "r1", status: "PENDING" } }),
        run: () => ({ status: 200, body: { id: "r1", status: "FAILED", result: "no such milestone: harvest" } }),
      }),
    )
    draw(<CommandCard />)

    const safe = await row("season phase")
    fireEvent.click(within(safe).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(safe.textContent).toContain("no such milestone: harvest"))
  })
})

describe("CommandCard - what it will not let be pressed", () => {
  it("keeps the button out of reach while a required argument is empty", async () => {
    const withArgument: AdminCommand = {
      ...SAFE,
      name: "access grant",
      arguments: [{ name: "player", kind: "PLAYER", required: true }],
    }
    vi.stubGlobal("fetch", backend({ commands: [withArgument] }))
    draw(<CommandCard />)

    const only = await row("access grant")
    const button = asButton(within(only).getByRole("button", { name: /Run/ }))
    expect(button.disabled).toBe(true)

    // `.trim()` in `missing` keeps whitespace from counting as an argument.
    fireEvent.change(within(only).getByLabelText("player"), { target: { value: "   " } })
    expect(button.disabled).toBe(true)

    fireEvent.change(within(only).getByLabelText("player"), { target: { value: "ally" } })
    expect(button.disabled).toBe(false)
  })

  it("says so plainly when no command is released for the interface at all", async () => {
    /** An empty list of WEB declarations is a statement, not a loading state. */
    vi.stubGlobal("fetch", backend({ commands: [] }))
    draw(<CommandCard />)

    expect(await screen.findByText(/No command is released to the interface/)).toBeTruthy()
  })
})

describe("accountOptions - the picker names people, not snowflakes", () => {
  /** A Radix `Select` does not open under jsdom, so the labelling is held on the function that builds the options. */
  const PEOPLE = [
    {
      discordId: "214906139328839681",
      discordDisplayName: "Ally",
      minecraftUuid: "11111111-2222-3333-4444-555555555555",
    },
    { discordId: "300000000000000002", discordUsername: "bob" },
    { discordId: "999999999999999999" },
  ]

  function drawOptions() {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url === "/api/people") return json(200, PEOPLE)
        if (url === "/api/settings") return json(200, { minecraftHeadBaseUrl: "" })
        throw new Error(`the options asked for ${url}, which this test did not expect`)
      }),
    )
    const options = accountOptions(PEOPLE)
    draw(
      <ul>
        {options.map((option) => (
          <li key={option.value}>{option.label}</li>
        ))}
      </ul>,
    )
    return options
  }

  it("labels each option with the person and never with the id", async () => {
    const options = drawOptions()

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.getByText("bob")).toBeTruthy()
    expect(screen.getByText("linked")).toBeTruthy()
    expect(screen.getAllByText("not linked")).toHaveLength(2)
    expect(document.body.textContent).not.toMatch(/\d{17,20}/)
    // The id is still what gets submitted, only not what a human reads.
    expect(options.map((option) => option.value)).toEqual([
      "214906139328839681",
      "300000000000000002",
      "999999999999999999",
    ])
  })

  it("says so for somebody with no name at all, rather than printing the id", async () => {
    drawOptions()

    expect(await screen.findByText("no Discord name on record")).toBeTruthy()
    expect(document.body.textContent).not.toContain("999999999999999999")
  })

  it("answers an empty list while the roster is still loading", () => {
    expect(accountOptions(undefined)).toEqual([])
  })
})
