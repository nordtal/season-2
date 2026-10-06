import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { AccessPage } from "@/pages/access"
import { IDENTIFIER_PATTERN } from "@/components/steward/identity"
import { toast } from "sonner"
import { asButton } from "@/lib/test-elements"
import {
  ME,
  actionsOf,
  assertElement,
  backend,
  clickRowAction,
  draw,
  manyMatches,
  openActions,
  openGrant,
  person,
  requestBody,
  rowFor,
  toastDescription,
} from "@/pages/access.fixtures"

/**
 * The Access page: faces for identifiers, search over four fields, and filtering before paging.
 *
 * `unlink` is a row action, covered lower down; `settle` is in `payments.test.tsx`, against the same backend.
 */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AccessPage - faces instead of identifiers", () => {
  it("never draws a raw Discord id or Minecraft uuid in the table", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("Ally")

    expect(document.body.textContent).not.toContain("214906139328839681")
    expect(document.body.textContent).not.toContain("11111111-2222-3333-4444-555555555555")
  })

  it("reveals the Discord id in the identity popover, on request", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const trigger = await screen.findByText("Ally")
    fireEvent.click(assertElement(trigger.closest("button"), "the Ally trigger button"))

    const field = await screen.findByLabelText("Discord-ID")
    if (!(field instanceof HTMLInputElement)) throw new Error("expected an <input>")
    expect(field.value).toBe("214906139328839681")
  })
})

describe("AccessPage - the guild column", () => {
  it("says nothing extra for an ordinary member", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("Ally")
    expect(screen.queryByText("Member")).toBeNull()
  })

  it("still marks somebody who left the guild", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("bob")
    expect(screen.getByText("left")).toBeTruthy()
  })

  it("still marks somebody who was banned", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("carol")
    expect(screen.getByText("banned")).toBeTruthy()
  })
})

describe("AccessPage - search over four things", () => {
  it("finds an account by its Minecraft name, which is not what is displayed", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "AliceMC" } })

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.queryByText("bob")).toBeNull()
  })

  it("finds an account by its Minecraft uuid, although the uuid is never shown", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), {
      target: { value: "11111111-2222-3333-4444-555555555555" },
    })

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("finds an account by its Discord id", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "214906139328839681" } })

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.queryByText("bob")).toBeNull()
  })

  it("finds an account by its Discord username", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "bob" } })

    expect(await screen.findByText("bob")).toBeTruthy()
    expect(screen.queryByText("Ally")).toBeNull()
  })
})

describe("AccessPage - the Minecraft column draws a face", () => {
  it("shows the in-game name and a head for a linked person, not a badge", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const row = await rowFor("Ally")
    const cell = assertElement(row.querySelector('[data-label="Minecraft"]'), "the Minecraft cell")
    expect(within(cell).getByText("AliceMC")).toBeTruthy()
    expect(within(cell).queryByText("linked")).toBeNull()
    expect(cell.querySelector("img")).toBeTruthy()
  })

  it("keeps the not-linked badge, with its warning colour, for somebody with no Minecraft account", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const row = await rowFor("bob")
    const cell = assertElement(row.querySelector('[data-label="Minecraft"]'), "the Minecraft cell")
    expect(within(cell).getByText("not linked")).toBeTruthy()
  })

  it("never puts the Minecraft uuid in that cell", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const row = await rowFor("Ally")
    const cell = assertElement(row.querySelector('[data-label="Minecraft"]'), "the Minecraft cell")
    expect(cell.textContent).not.toContain("11111111-2222-3333-4444-555555555555")
  })
})

describe("AccessPage - pagination filters the whole roster before it pages", () => {
  // Fifteen seconds: it draws 21 rows and waits twice, close to the default budget on a busy machine.
  it(
    "holds 21 matches over two pages of at most 20, without the second page vanishing from the search",
    { timeout: 15_000 },
    async () => {
      vi.stubGlobal("fetch", backend({ people: manyMatches }))
      draw(<AccessPage />)

      await screen.findByText("searchable-0")

      fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "searchable" } })

      // Page 1: twenty rows, and the one count names them against the whole filtered total.
      await waitFor(() => expect(screen.getAllByText(/searchable-/).length).toBe(20))
      expect(screen.getByText("1-20 of 21 users")).toBeTruthy()

      fireEvent.click(screen.getByRole("button", { name: /next/i }))

      await waitFor(() => expect(screen.getAllByText(/searchable-/).length).toBe(1))
      expect(screen.getByText("21-21 of 21 users")).toBeTruthy()
    },
  )
})

/** Play time is listed and overridable, since the donor tier derives from `player_playtime.seconds` alone. */
describe("AccessPage - play time in the list, and overridable", () => {
  it("prints an account's play time as a span, not as a number of seconds", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "alice", playtimeSeconds: 32400 })] }))
    draw(<AccessPage />)

    const cell = await screen.findByText("9h")
    expect(cell).not.toBeNull()
    expect(screen.queryByText("32400")).toBeNull()
  })

  it("says nothing for somebody who has never been online, rather than no time at all", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "alice" })] }))
    draw(<AccessPage />)

    const row = await screen.findByText("alice")
    expect(row).not.toBeNull()
    expect(screen.queryByText("0m")).toBeNull()
  })

  it("shows the minutes as well, which a two-unit span would have dropped", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", playtimeSeconds: 86_400 + 6 * 3_600 + 30 * 60 })],
      }),
    )
    draw(<AccessPage />)

    expect(await screen.findByText("1d 6h 30m")).not.toBeNull()
  })

  it("asks in days, hours and minutes rather than in decimal hours", async () => {
    // Three fields, so 37.5 hours typed as 375 does not pass as days.
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", playtimeSeconds: 0 })],
        playtimePost: (url, body) => {
          calls.push({ url, body })
          return { status: 202, body: { id: "a-playtime", status: "PENDING" } }
        },
      }),
    )
    draw(<AccessPage />)

    await clickRowAction(/play ?time/i)
    fireEvent.change(await screen.findByLabelText(/days/i), { target: { value: "1" } })
    fireEvent.change(screen.getByLabelText(/hours/i), { target: { value: "6" } })
    fireEvent.change(screen.getByLabelText(/minutes/i), { target: { value: "30" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({ seconds: 86_400 + 6 * 3_600 + 30 * 60 })
  })

  it("carries an out-of-range field instead of refusing it", async () => {
    // "0 days 50 hours" is two days and two hours; an empty field counts as zero.
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", playtimeSeconds: 0 })],
        playtimePost: (url, body) => {
          calls.push({ url, body })
          return { status: 202, body: { id: "a-playtime", status: "PENDING" } }
        },
      }),
    )
    draw(<AccessPage />)

    await clickRowAction(/play ?time/i)
    fireEvent.change(await screen.findByLabelText(/days/i), { target: { value: "" } })
    fireEvent.change(screen.getByLabelText(/hours/i), { target: { value: "50" } })
    fireEvent.change(screen.getByLabelText(/minutes/i), { target: { value: "" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].body).toEqual({ seconds: 50 * 3_600 })
  })

  it("writes the override in seconds, from hours typed into the dialog", async () => {
    const calls: { url: string; body: unknown }[] = []
    const fetcher = backend({
      people: () => [person({ discordUsername: "alice", playtimeSeconds: 3600 })],
      playtimePost: (url, body) => {
        calls.push({ url, body })
        return { status: 202, body: { id: "a-playtime", status: "PENDING" } }
      },
    })
    vi.stubGlobal("fetch", fetcher)
    draw(<AccessPage />)

    await clickRowAction(/play ?time/i)
    const hours = await screen.findByLabelText(/hours/i)
    fireEvent.change(hours, { target: { value: "12" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0].url).toBe("/api/people/100000000000000001/playtime")
    expect(calls[0].body).toEqual({ seconds: 43200 })
  })
})

describe("AccessPage - unlink as a row action", () => {
  it("offers Unlink on a linked person", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    expect(within(actions).getByRole("button", { name: /unlink/i })).toBeTruthy()
  })

  it("sends that person's own Discord id, with no picker to get wrong", async () => {
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const actions = await openActions("Ally")
    fireEvent.click(within(actions).getByRole("button", { name: /unlink/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Unlink" }))

    await waitFor(() => {
      const call = fetched.mock.calls.find(([url]) => url === "/api/access/unlink")
      expect(call).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/access/unlink")!
    expect(requestBody(call[1])).toEqual({
      discordId: "214906139328839681",
    })
  })
})

/** An action is drawn only when the row's state allows it, so linked Ally and bare bob differ. */
describe("AccessPage - the actions of a row depend on that row", () => {
  it("offers nothing to unlink for somebody with no Minecraft account", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    // bob has no minecraftUuid, and the row's own state is the only thing that hides the button.
    expect(await actionsOf("bob")).not.toContain("Unlink")
    expect(await actionsOf("Ally")).toContain("Unlink")
  })

  it("offers no Periods to somebody who has never had one", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    // No `accessUntil` means no period was ever written, so the dialog would open on nothing.
    expect(await actionsOf("bob")).not.toContain("Periods")
    expect(await actionsOf("Ally")).toContain("Periods")
  })

  it("offers no Revoke where there is nothing running to take away", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).not.toContain("Revoke")
    expect(await actionsOf("Ally")).toContain("Revoke")
  })

  it("always offers Grant, because more access can always be given", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).toContain("Grant")
    expect(await actionsOf("Ally")).toContain("Grant")
  })

  it("puts every row's actions behind one popover, however few there are, so the rows line up", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    /** Ally has four; bob has one, and still gets the popover. */
    const ally = await rowFor("Ally")
    const bob = await rowFor("bob")
    expect(within(ally).getByRole("button", { name: /^Actions for/ })).toBeTruthy()
    expect(within(bob).getByRole("button", { name: /^Actions for/ })).toBeTruthy()
    expect(within(bob).queryByRole("button", { name: "Grant" })).toBeNull()
  })

  it("leaves a person's absent values out of the phone card and keeps a paying person's missing link", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [
          person({ discordUsername: "quiet" }),
          person({ discordId: "2", discordUsername: "payer", accessActive: true, accessUntil: "2027-01-01T00:00:00Z" }),
        ],
      }),
    )
    draw(<AccessPage />)

    const quiet = await rowFor("quiet")
    const absent = (row: HTMLElement) =>
      [...row.querySelectorAll('[data-phone="off"]')].map((cell) => cell.getAttribute("data-label"))
    expect(absent(quiet)).toEqual(["Minecraft", "Roles", "Playtime"])
    expect(absent(await rowFor("payer"))).toEqual(["Roles", "Playtime"])
  })
})

/** Every access change is a request in the bot's inbox, since only the bot applies the role and tells the person. */
describe("AccessPage - access changes are asked of the bot", () => {
  it("refuses a grant longer than 365 days before it is sent", async () => {
    const fetched = backend()
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.change(within(dialog).getByLabelText("Days"), { target: { value: "366" } })
    expect(asButton(within(dialog).getByRole("button", { name: "Grant" })).disabled).toBe(true)
    fireEvent.change(within(dialog).getByLabelText("Days"), { target: { value: "365" } })
    expect(asButton(within(dialog).getByRole("button", { name: "Grant" })).disabled).toBe(false)
  })

  it("sends the grant to the bot's inbox and waits for its answer", async () => {
    const fetched = backend({
      answer: (kind) => ({
        id: "a-grant",
        kind,
        status: "DONE",
        result: { until: "2026-10-25T00:00:00Z" },
      }),
    })
    vi.stubGlobal("fetch", fetched)
    const success = vi.spyOn(toast, "success")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(success).toHaveBeenCalled())
    const call = fetched.mock.calls.find(([url]) => url === "/api/access/grant")!
    expect(requestBody(call[1])).toEqual({
      discordId: "214906139328839681",
      days: 30,
    })
    expect(fetched.mock.calls.some(([url]) => url === "/api/access/requests/a-grant")).toBe(true)
    expect(fetched.mock.calls.some(([url]) => url.startsWith("/api/commands"))).toBe(false)
    success.mockRestore()
  })

  it("says what the bot said when it could not carry the change out", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        answer: (kind) => ({
          id: "a-grant",
          kind,
          status: "FAILED",
          result: { error: "the role could not be applied" },
        }),
      }),
    )
    const failure = vi.spyOn(toast, "error")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(failure).toHaveBeenCalled())
    expect(toastDescription(failure.mock.calls[0][1]?.description)).toContain("the role could not be applied")
    failure.mockRestore()
  })

  it("says nothing changed when the bot never picked the request up", async () => {
    vi.stubGlobal("fetch", backend({ answer: (kind) => ({ id: "a-grant", kind, status: "EXPIRED" }) }))
    const failure = vi.spyOn(toast, "error")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(failure).toHaveBeenCalled())
    expect(toastDescription(failure.mock.calls[0][1]?.description)).toContain("Nothing was changed")
    failure.mockRestore()
  })
})

describe("AccessPage - the generic command card is gone", () => {
  it("does not draw the 'Access commands' block any more", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findByText("Ally")
    expect(screen.queryByText("Access commands")).toBeNull()
  })
})

/** Admins are granted and revoked here; "Revoke admin" is drawn only for an admin below the signed in one. */
describe("AccessPage - the admin tree", () => {
  const TREE = [
    person({ discordId: ME, discordUsername: "me", admin: true, adminGrantedBy: null }),
    person({ discordId: "510000000000000001", discordUsername: "mine", admin: true, adminGrantedBy: ME }),
    person({
      discordId: "510000000000000002",
      discordUsername: "theirs",
      admin: true,
      adminGrantedBy: "510000000000000001",
    }),
    person({ discordId: "510000000000000003", discordUsername: "plain" }),
    person({ discordId: "510000000000000004", discordUsername: "gone", memberState: "LEFT" }),
  ]

  it("offers Make admin on a member who is none, and never on somebody who left", async () => {
    vi.stubGlobal("fetch", backend({ people: () => TREE }))
    draw(<AccessPage />)
    expect(await actionsOf("plain")).toContain("Make admin")
    cleanup()
    draw(<AccessPage />)
    expect(await actionsOf("gone")).not.toContain("Make admin")
  })

  it("offers Revoke admin below the one signed in, the whole way down", async () => {
    vi.stubGlobal("fetch", backend({ people: () => TREE }))
    draw(<AccessPage />)
    await waitFor(async () => expect(await actionsOf("theirs")).toContain("Revoke admin"))
  })

  it("never offers Revoke admin on the one signed in", async () => {
    vi.stubGlobal("fetch", backend({ people: () => TREE }))
    draw(<AccessPage />)
    await screen.findByText("mine")
    expect(await actionsOf("me")).not.toContain("Revoke admin")
  })

  it("sends the row's own Discord id to the grant", async () => {
    const fetched = backend({ people: () => TREE })
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const row = await rowFor("plain")
    const trigger = within(row).queryByRole("button", { name: /^Actions for/ })
    const scope = trigger ? (fireEvent.click(trigger), await screen.findByRole("dialog")) : row
    fireEvent.click(within(scope).getByRole("button", { name: /make admin/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Make admin" }))

    await waitFor(() => {
      expect(fetched.mock.calls.find(([url]) => url === "/api/admins/grant")).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/admins/grant")!
    expect(requestBody(call[1])).toEqual({
      discordId: "510000000000000003",
    })
  })
})

describe("AccessPage - playing without the resource pack", () => {
  const LINKED = { minecraftUuid: "11111111-2222-3333-4444-555555555555", mcName: "Painter" }
  const ROSTER = [
    person({ discordId: ME, discordUsername: "me", admin: true, adminGrantedBy: null }),
    person({ discordId: "520000000000000001", discordUsername: "painter", ...LINKED }),
    person({
      discordId: "520000000000000002",
      discordUsername: "exempt",
      ...LINKED,
      minecraftUuid: "66666666-2222-3333-4444-555555555555",
      packExemptBy: ME,
      packExemptAt: "2026-09-27T12:00:00Z",
    }),
    person({ discordId: "520000000000000003", discordUsername: "unlinked" }),
  ]

  it("offers Skip resource pack last, and only for somebody with a Minecraft account", async () => {
    vi.stubGlobal("fetch", backend({ people: () => ROSTER }))
    draw(<AccessPage />)
    const actions = await actionsOf("painter")
    expect(actions.at(-1)).toBe("Skip resource pack")
    cleanup()
    draw(<AccessPage />)
    expect(await actionsOf("unlinked")).not.toContain("Skip resource pack")
  })

  it("marks an exempt player in the list and offers to enforce the pack again", async () => {
    vi.stubGlobal("fetch", backend({ people: () => ROSTER }))
    draw(<AccessPage />)
    const row = await rowFor("exempt")
    expect(within(row).getByText("No resource pack")).toBeTruthy()
    expect(within(await rowFor("painter")).queryByText("No resource pack")).toBeNull()
    expect(await actionsOf("exempt")).toContain("Enforce resource pack")
  })

  it("sends the row's own Discord id to the exemption", async () => {
    const fetched = backend({ people: () => ROSTER })
    vi.stubGlobal("fetch", fetched)
    draw(<AccessPage />)

    const scope = await openActions("painter")
    fireEvent.click(within(scope).getByRole("button", { name: "Skip resource pack" }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Skip resource pack" }))

    await waitFor(() => {
      expect(fetched.mock.calls.find(([url]) => url === "/api/pack-exemptions/exempt")).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/pack-exemptions/exempt")!
    expect(requestBody(call[1])).toEqual({ discordId: "520000000000000001" })
  })
})
