import { cleanup, fireEvent, screen, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { PersonPage } from "@/pages/person"
import { assertElement, backend, draw } from "@/pages/access.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const ALLY = "214906139328839681"
const BOB = "300000000000000002"

const storedState = (mode: string) => ({
  server: "smp",
  mode,
  status: "STORED",
  level: 7,
  progress: 0.5,
  saved: "2026-10-20T09:00:00Z",
  stacks: [],
})

const ALLYS_PAYMENT = {
  id: "p1",
  reference: "AB12CD",
  discordId: ALLY,
  days: 30,
  amountCents: 500,
  donationCents: 0,
  status: "OPEN",
  created: "2026-09-01T00:00:00Z",
  expires: "2026-09-20T00:00:00Z",
}

const ALLYS_PERIOD = {
  id: "g1",
  discordId: ALLY,
  source: "ADMIN",
  created: "2026-09-01T00:00:00Z",
  validFrom: "2026-09-01T00:00:00Z",
  validUntil: "2027-01-01T00:00:00Z",
}

const ALLYS_ENTRY = {
  id: "j1",
  occurred: "2026-09-18T09:00:00Z",
  action: "GRANT_ACCESS",
  actor: { kind: "PERSON", person: "500000000000000000" },
  subject: ALLY,
  line: {
    key: "journal.grant-access",
    args: { days: { kind: "number", value: 30 }, until: { kind: "instant", value: "2026-10-18T09:00:00Z" } },
  },
}

function allysBackend() {
  return backend({
    personPayments: (discordId) => (discordId === ALLY ? [ALLYS_PAYMENT] : []),
    grants: (discordId) => (discordId === ALLY ? [ALLYS_PERIOD] : []),
    journal: () => [ALLYS_ENTRY],
  })
}

describe("PersonPage - one person on an address of their own", () => {
  it("is titled with the name and draws their figures", async () => {
    vi.stubGlobal("fetch", allysBackend())
    draw(<PersonPage />, `/access/${ALLY}`)

    expect(await screen.findByRole("heading", { level: 1, name: "Ally" })).toBeTruthy()
    expect(await screen.findByText("AliceMC")).toBeTruthy()
    expect(screen.getByText("Language")).toBeTruthy()
    expect(screen.getByText("en")).toBeTruthy()
  })

  it("reveals the Discord id in the identity popover, on request", async () => {
    vi.stubGlobal("fetch", allysBackend())
    draw(<PersonPage />, `/access/${ALLY}`)

    await screen.findByRole("heading", { level: 1, name: "Ally" })
    const face = assertElement(screen.getByText("Guild").parentElement, "the guild figure")
    fireEvent.click(assertElement(within(face).getByText("Ally").closest("button"), "the Ally trigger button"))

    const field = await screen.findByLabelText("Discord-ID")
    if (!(field instanceof HTMLInputElement)) throw new Error("expected an <input>")
    expect(field.value).toBe(ALLY)
  })

  it("asks for this person's own payments and journal, not a page of everybody's", async () => {
    const fetch = allysBackend()
    vi.stubGlobal("fetch", fetch)
    draw(<PersonPage />, `/access/${ALLY}`)

    expect(await screen.findByText("AB12CD")).toBeTruthy()
    expect((await screen.findAllByText("Access granted")).length).toBeGreaterThan(0)
    const asked = fetch.mock.calls.map(([url]) => url)
    expect(asked).toContain(`/api/people/${ALLY}/payments`)
    expect(asked).not.toContain("/api/payments")
    expect(asked.some((url) => url.startsWith("/api/journal") && url.includes(`q=${ALLY}`))).toBe(true)
  })

  it("names nobody concerned in the journal, since every entry there concerns them", async () => {
    vi.stubGlobal("fetch", allysBackend())
    draw(<PersonPage />, `/access/${ALLY}`)

    const journal = assertElement((await screen.findByText("Journal")).closest("section"), "the journal panel")
    await within(journal).findAllByText("Access granted")
    expect(within(journal).queryByText("Ally")).toBeNull()
  })

  it("lists their periods without a dialog to open first", async () => {
    vi.stubGlobal("fetch", allysBackend())
    draw(<PersonPage />, `/access/${ALLY}`)

    const periods = assertElement((await screen.findByText("Periods")).closest("section"), "the periods panel")
    expect(await within(periods).findByText("by hand")).toBeTruthy()
    expect(within(periods).getByText("running")).toBeTruthy()
  })

  it("offers the actions as buttons, and Revoke asks before it acts", async () => {
    vi.stubGlobal("fetch", allysBackend())
    draw(<PersonPage />, `/access/${ALLY}`)

    await screen.findByRole("heading", { level: 1, name: "Ally" })
    expect(screen.queryByRole("button", { name: /^Actions for/ })).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Revoke" }))
    expect(await screen.findByText("Revoke access?")).toBeTruthy()
  })

  it("draws empty panels for somebody with no payment and no entry", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<PersonPage />, `/access/${BOB}`)

    expect(await screen.findByText("No payment request yet")).toBeTruthy()
    expect(await screen.findByText("Nothing written about them yet")).toBeTruthy()
  })

  it("says nobody has this id rather than drawing an empty person", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<PersonPage />, "/access/999999999999999999")

    expect(await screen.findByText("Nobody by this id")).toBeTruthy()
    expect(screen.queryByText("Periods")).toBeNull()
  })

  it("draws a stored inventory and removes the stack picked, naming what the slot showed", async () => {
    const removed: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        inventories: () => ({
          linked: true,
          states: [
            {
              server: "smp",
              mode: "SURVIVAL",
              status: "STORED",
              level: 7,
              progress: 0.5,
              saved: "2026-10-20T09:00:00Z",
              stacks: [{ container: "ENDER_CHEST", slot: 3, item: "minecraft:oak_log", amount: 12 }],
            },
          ],
        }),
        removeStack: (_url, body) => removed.push(body),
      }),
    )
    draw(<PersonPage />, `/access/${ALLY}`)

    const panel = assertElement((await screen.findByText("Inventories")).closest("section"), "the inventories panel")
    expect(await within(panel).findByText("Stored")).toBeTruthy()
    expect(within(panel).getByText("Level 7")).toBeTruthy()
    fireEvent.click(within(panel).getByRole("button", { name: "12 minecraft:oak_log" }))
    fireEvent.click(await screen.findByRole("button", { name: "Remove" }))

    await vi.waitFor(() =>
      expect(removed).toEqual([
        { server: "smp", mode: "SURVIVAL", container: "ENDER_CHEST", slot: 3, item: "minecraft:oak_log", amount: 12 },
      ]),
    )
  })
  it("opens a Survival ender chest in the game on its own server, and a Creative one not at all", async () => {
    const opened: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        inventories: () => ({ linked: true, states: [storedState("SURVIVAL"), storedState("CREATIVE")] }),
        openInventory: (body) => opened.push(body),
      }),
    )
    draw(<PersonPage />, `/access/${ALLY}`)

    const panel = assertElement((await screen.findByText("Inventories")).closest("section"), "the inventories panel")
    const buttons = await within(panel).findAllByRole("button", { name: "Edit in the game" })
    expect(buttons).toHaveLength(2)
    fireEvent.click(assertElement(buttons[1] ?? null, "the ender chest's button"))

    await vi.waitFor(() => expect(opened).toEqual([{ server: "smp", container: "ENDER_CHEST" }]))
    fireEvent.mouseDown(within(panel).getByRole("tab", { name: /Creative/ }))
    await vi.waitFor(() => expect(within(panel).queryByRole("button", { name: "Edit in the game" })).toBeNull())
  })
})
