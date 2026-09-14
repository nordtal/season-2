import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { SeasonPage } from "@/pages/season"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The reason for a phase change, and where it must not end up.
 *
 * The phase is the door policy: it decides who may join and where they land, and the switch is in
 * force on the next join with no restart anywhere. It is therefore the one setting in the interface
 * that asks twice, and the sentence typed into that second question is written into the journal
 * next to the operator's name.
 *
 * **A sentence that was typed and then cancelled must not survive the dialog.** `reason` is state
 * on the card, not on the dialog, so a cleared-only-on-success version leaves the abandoned
 * sentence in the field - and the *next* confirmation, about a different phase, sends it. The
 * journal then carries a reason for a change nobody gave it, which is worse than an empty one:
 * an empty reason is honest, and a wrong one is evidence.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

const SEASON = {
  phase: "PRE_EVENT",
  launch: "2026-10-01T18:00:00Z",
  smpStart: "2026-10-08T18:00:00Z",
}

/** The page's own two routes, plus the command catalogue the card inside it asks for. */
function backend(over: { phase?: () => { status: number; body: unknown } } = {}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/season/phase" && init?.method === "POST") {
      const answer = over.phase?.() ?? { status: 200, body: SEASON }
      return json(answer.status, answer.body)
    }
    if (url === "/api/season") return json(200, SEASON)
    if (url === "/api/commands") return json(200, [])
    throw new Error(`the page asked for ${url}, which this test did not expect`)
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

/** Press "Switch" on the row of one phase, found by the constant printed beside its label. */
async function ask(phase: string): Promise<HTMLElement> {
  const name = await screen.findByText(phase)
  const card = name.closest("div.rounded-md")
  if (!card) throw new Error(`the row of ${phase} is not shaped the way this test assumed`)
  fireEvent.click(within(card as HTMLElement).getByRole("button", { name: "Switch" }))
  return screen.findByRole("alertdialog")
}

const reasonField = () => screen.getByLabelText("Reason") as HTMLInputElement

/** What the page sent, as the backend would have read it. */
function sentPhaseChange(fetched: ReturnType<typeof backend>) {
  const call = fetched.mock.calls.find(([url, init]) => url === "/api/season/phase" && (init as RequestInit | undefined)?.method === "POST")
  if (!call) throw new Error("no phase change was sent at all")
  return JSON.parse(String((call[1] as RequestInit).body))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("SeasonPage - the reason that was typed and abandoned", () => {
  it("is gone from the field when the dialog is cancelled", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    await ask("PRE_LAUNCH")
    fireEvent.change(reasonField(), { target: { value: "No - wrong phase after all" } })
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    await ask("MAINTENANCE")
    expect(reasonField().value).toBe("")
  })

  it("is gone when the dialog is dismissed with Escape rather than with the button", async () => {
    // `onOpenChange` is what clears it, so every way out of the dialog has to go through it -
    // Escape and a click on the overlay included. Clearing it in the Cancel handler alone would
    // pass the test above and leak here.
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    const dialog = await ask("PRE_LAUNCH")
    fireEvent.change(reasonField(), { target: { value: "Typo" } })
    fireEvent.keyDown(dialog, { key: "Escape", code: "Escape" })

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    await ask("MAINTENANCE")
    expect(reasonField().value).toBe("")
  })

  it("is not what the next phase change sends, which is the whole point", async () => {
    // The defect as it would be read six months later: a journal entry against MAINTENANCE
    // carrying a sentence somebody typed about PRE_LAUNCH and then thought better of.
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<SeasonPage />)

    await ask("PRE_LAUNCH")
    fireEvent.change(reasonField(), { target: { value: "No - wrong phase after all" } })
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }))
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())

    const second = await ask("MAINTENANCE")
    fireEvent.click(within(second).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(() => sentPhaseChange(fetched)).not.toThrow())
    expect(sentPhaseChange(fetched)).toEqual({ phase: "MAINTENANCE", reason: "" })
  })

  it("is sent when it was meant, so that the clearing is not simply a broken field", async () => {
    // The other direction. A test that only ever asserts "" would pass against a field that never
    // works at all.
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.change(reasonField(), { target: { value: "Postgres wird umgezogen" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(() => sentPhaseChange(fetched)).not.toThrow())
    expect(sentPhaseChange(fetched)).toEqual({ phase: "MAINTENANCE", reason: "Postgres wird umgezogen" })
  })

  it("is gone after a switch that went through", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.change(reasonField(), { target: { value: "Postgres wird umgezogen" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull())
    await ask("PRE_LAUNCH")
    expect(reasonField().value).toBe("")
  })

  it("asks before it switches at all, and the question names the admission rule", async () => {
    // Not decoration: "Before launch" tells nobody whether their players can log in, and the
    // dialog is the last place this can be said before the door changes.
    vi.stubGlobal("fetch", backend())
    draw(<SeasonPage />)

    const dialog = await ask("PRE_LAUNCH")

    expect(dialog.textContent).toContain("Admins only")
    expect(dialog.textContent).toContain("from the next")
  })
})

describe("SeasonPage - a switch the backend refuses", () => {
  it("keeps the dialog open, because the phase has not changed", async () => {
    vi.stubGlobal("fetch", backend({ phase: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Switch" })).toBeTruthy())
    expect(screen.queryByRole("alertdialog")).not.toBeNull()
  })

  it("shows the operator why, inside the dialog they are looking at", async () => {
    /*
     * This was a defect when it was written, and the same one `command-card.tsx` had one file over:
     * `{change.error ? <Failure …/> : null}` sat in the `CardContent`, which the open AlertDialog
     * covers and marks `aria-hidden` - so a refused phase change left the dialog open, the
     * "Switch" button enabled again, and the reason nowhere the operator could see it. Pressing
     * it a second time is the obvious next move, and it would have failed the same way, silently.
     * The refusal is now repeated inside the dialog, and the copy in the card is only drawn while
     * the dialog is shut.
     */
    vi.stubGlobal("fetch", backend({ phase: () => ({ status: 503, body: { error: "The database is not answering." } }) }))
    draw(<SeasonPage />)

    const dialog = await ask("MAINTENANCE")
    fireEvent.click(within(dialog).getByRole("button", { name: "Switch" }))

    // `hidden: true` because Radix marks everything outside the open dialog aria-hidden, and the
    // filter because the page carries a standing `role="alert"` of its own ("Nothing is carried
    // between seasons"), which is not the sentence under test.
    const refusals = (scope: { queryAllByRole: typeof screen.queryAllByRole }) =>
      scope
        .queryAllByRole("alert", { hidden: true })
        .filter((one) => one.textContent?.includes("The database is not answering."))

    // It IS rendered - in the card underneath, which the overlay covers.
    await waitFor(() => expect(refusals(screen)).toHaveLength(1))

    // And this is the assertion that fails: it is not in the dialog the operator is looking at.
    expect(refusals(within(dialog))).toHaveLength(1)
  })
})
