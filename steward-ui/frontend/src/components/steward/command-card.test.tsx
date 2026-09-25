import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { AdminCommand } from "@/lib/api"
import { CommandCard, accountOptions } from "@/components/steward/command-card"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * A refusal has to be where the operator is looking.
 *
 * While the confirmation is open it covers the card, so a `Failure` rendered in the row behind it
 * is a refusal nobody reads - and the button that produced it is still sitting there in the dialog
 * looking as though the click did nothing. That is the one thing these tests are about: the same
 * error, in whichever of the two places is on top at the time, and never in both.
 *
 * Nothing here knows what a command does; the catalogue comes from `/api/commands`, which is what
 * keeps this card from becoming a second, quietly diverging copy of the declarations.
 */

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

/**
 * The three routes the card uses. POST and GET share a prefix, so they are told apart by method -
 * `/api/commands/{id}` is the poll and `/api/commands` is the request.
 */
function backend(over: {
  commands?: AdminCommand[]
  ask?: () => { status: number; body: unknown }
  run?: () => { status: number; body: unknown }
} = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
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

/** The row of one command, found by the `<span class="font-mono">` carrying its name. */
async function row(name: string): Promise<HTMLElement> {
  const label = await screen.findByText(name)
  const found = label.closest("div.rounded-md")
  if (!found) throw new Error(`the row of ${name} is not shaped the way this test assumed`)
  return found as HTMLElement
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("CommandCard - a command that refuses to be written", () => {
  it("shows the refusal inside the confirmation, where the operator is looking", async () => {
    // The defect: the dialog covers the card, so the `Failure` in the row behind it was a sentence
    // nobody could read, under a button that looked as though nothing had happened.
    vi.stubGlobal("fetch", backend({ ask: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<CommandCard />)

    fireEvent.click(within(await row("milestone unlock")).getByRole("button", { name: /Run/ }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: /Run/ }))

    await waitFor(() => expect(within(dialog).queryByRole("alert")).not.toBeNull())
    expect(within(dialog).getByRole("alert").textContent).toContain("The database is not answering.")
    // And in exactly one place. `hidden: true` is not padding: Radix marks everything outside the
    // open dialog `aria-hidden`, so a second copy left in the row behind the overlay is invisible
    // to a default `getAllByRole` and this assertion would pass over the very thing it is for.
    expect(screen.getAllByRole("alert", { hidden: true })).toHaveLength(1)
  })

  it("leaves the confirmation open, because the command has not been written", async () => {
    // Closing it on failure would look like success. `setConfirming(false)` is deliberately in
    // `onSuccess` and nowhere else.
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
    // The other half of the `!confirming` condition: with nothing covering the card the sentence
    // belongs where the button is.
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
    // EXPIRED means the service that owns the command is not listening. That is a different
    // errand from FAILED, and collapsing the two into "it did not work" sends somebody to the
    // wrong log.
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
    // The row exists and the command may well be running; what is broken is the asking. Drawing
    // "Being carried out." here would be an assertion nothing supports.
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
    const button = within(only).getByRole("button", { name: /Run/ }) as HTMLButtonElement
    expect(button.disabled).toBe(true)

    // Whitespace is not an argument - `.trim()` in `missing` is what makes that true.
    fireEvent.change(within(only).getByLabelText("player"), { target: { value: "   " } })
    expect(button.disabled).toBe(true)

    fireEvent.change(within(only).getByLabelText("player"), { target: { value: "till" } })
    expect(button.disabled).toBe(false)
  })

  it("says so plainly when no command is released for the interface at all", async () => {
    // The list is the declarations carrying Surface.WEB. An empty one is a statement, not a
    // loading state.
    vi.stubGlobal("fetch", backend({ commands: [] }))
    draw(<CommandCard />)

    expect(await screen.findByText(/No command is released to the interface/)).toBeTruthy()
  })
})

describe("accountOptions - the picker names people, not snowflakes (steward/124)", () => {
  /**
   * The options live inside a Radix `Select`, which does not open under jsdom - so the labelling
   * rule is held on the function that builds them rather than on the popup.
   */
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
    const options = accountOptions(PEOPLE as never)
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
    // The id is still what gets submitted - it is just not what a human reads while picking.
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
