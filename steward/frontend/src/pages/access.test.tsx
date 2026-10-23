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
 * The Access page: faces for identifiers, search over four fields, and filtering before paging. `settle` is in `payments.test.tsx`.
 */

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("AccessPage - faces instead of identifiers", () => {
  it("never draws a raw Discord id or Minecraft uuid in the table", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findAllByText("Ally")

    expect(document.body.textContent).not.toContain("214906139328839681")
    expect(document.body.textContent).not.toContain("11111111-2222-3333-4444-555555555555")
  })

  it("links a name to that person's page, where the identity card is", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const [name] = await screen.findAllByText("Ally")
    const link = assertElement(name?.closest("a") ?? null, "a link around Ally")
    expect(link.getAttribute("href")).toBe("/access/214906139328839681")
    expect(name?.closest("button")).toBeNull()
  })
})

describe("AccessPage - the guild column", () => {
  it("says nothing extra for an ordinary member", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findAllByText("Ally")
    expect(screen.queryByText("Member")).toBeNull()
  })

  it("still marks somebody who left the guild", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findAllByText("bob")
    expect(screen.getAllByText("left")).not.toHaveLength(0)
  })

  it("still marks somebody who was banned", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findAllByText("carol")
    expect(screen.getAllByText("banned")).not.toHaveLength(0)
  })
})

describe("AccessPage - search over four things", () => {
  it("finds an account by its Minecraft name, which is not what is displayed", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findAllByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "AliceMC" } })

    expect(await screen.findAllByText("Ally")).toBeTruthy()
    expect(screen.queryByText("bob")).toBeNull()
  })

  it("finds an account by its Minecraft uuid, although the uuid is never shown", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findAllByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), {
      target: { value: "11111111-2222-3333-4444-555555555555" },
    })

    expect(await screen.findAllByText("Ally")).toBeTruthy()
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("finds an account by its Discord id", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findAllByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "214906139328839681" } })

    expect(await screen.findAllByText("Ally")).toBeTruthy()
    expect(screen.queryByText("bob")).toBeNull()
  })

  it("finds an account by its Discord username", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)
    await screen.findAllByText("Ally")

    fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "bob" } })

    expect(await screen.findAllByText("bob")).toBeTruthy()
    expect(screen.queryByText("Ally")).toBeNull()
  })
})

describe("AccessPage - a person is one element", () => {
  it("draws the Discord and the Minecraft picture and both names in one link to the person's page", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const cell = assertElement((await rowFor("Ally")).querySelector(":scope > td:first-child"), "the person")
    const [link, ...more] = within(cell).getAllByRole("link")
    expect(more).toEqual([])
    expect(link?.getAttribute("href")).toBe("/access/214906139328839681")
    expect(link?.textContent).toContain("Ally")
    expect(link?.textContent).toContain("AliceMC")
    expect(within(cell).queryAllByRole("button")).toEqual([])
  })

  it("has no Minecraft or Roles column, and puts the badges after the names", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "boss", admin: true })] }))
    draw(<AccessPage />)

    const row = await rowFor("boss")
    expect(row.querySelector('[data-label="Minecraft"]')).toBeNull()
    expect(row.querySelector('[data-label="Roles"]')).toBeNull()
    expect(within(badges(row)).getByText("Admin")).toBeTruthy()
    expect([...document.querySelectorAll("thead th")].map((head) => head.textContent)).toEqual([
      "Person",
      "Access",
      "Playtime",
      "Aura",
      "Spins",
      "Last seen",
      "",
    ])
  })
})

describe("AccessPage - pagination filters the whole roster before it pages", () => {
  // Fifteen seconds, because it draws 21 rows and waits twice.
  it(
    "holds 21 matches over two pages of at most 20, without the second page vanishing from the search",
    { timeout: 15_000 },
    async () => {
      vi.stubGlobal("fetch", backend({ people: manyMatches }))
      draw(<AccessPage />)

      await screen.findAllByText("searchable-0")

      fireEvent.change(screen.getByLabelText(/filter/i), { target: { value: "searchable" } })

      await waitFor(() => expect(document.querySelectorAll("tbody tr")).toHaveLength(20))
      expect(screen.getByText("1-20 of 21 users")).toBeTruthy()

      fireEvent.click(screen.getByRole("button", { name: /next/i }))

      await waitFor(() => expect(document.querySelectorAll("tbody tr")).toHaveLength(1))
      expect(screen.getByText("21-21 of 21 users")).toBeTruthy()
    },
  )
})

/**
 * Play time is listed and overridable, because the donor tier derives from `player_playtime.seconds` alone.
 */
describe("AccessPage - play time in the list, and overridable", () => {
  it("prints an account's play time as a span, not as a number of seconds", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "alice", playtimeSeconds: 32400 })] }))
    draw(<AccessPage />)

    expect(await screen.findAllByText("9h")).not.toHaveLength(0)
    expect(screen.queryByText("32400")).toBeNull()
  })

  it("says nothing for somebody who has never been online, rather than no time at all", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "alice" })] }))
    draw(<AccessPage />)

    const row = await screen.findAllByText("alice")
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

    expect(await screen.findAllByText("1d 6h 30m")).not.toBeNull()
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
    // "0 days 50 hours" is two days and two hours. An empty field counts as zero.
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

describe("AccessPage - aura and spins in the list, and settable", () => {
  it("lists each person's aura and spins", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "alice", aura: -40, spins: 3 })] }))
    draw(<AccessPage />)

    expect(await screen.findAllByText("-40")).not.toHaveLength(0)
    expect(screen.getAllByText("3")).not.toHaveLength(0)
  })

  it("sets the aura, below zero too", async () => {
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", aura: 10 })],
        smpPost: (url, body) => calls.push({ url, body }),
      }),
    )
    draw(<AccessPage />)

    await clickRowAction(/^aura$/i)
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.change(within(dialog).getByLabelText(/^aura$/i), { target: { value: "-25" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0]).toEqual({ url: "/api/people/100000000000000001/aura", body: { aura: -25 } })
  })

  it("sets the spins as a whole number not below zero", async () => {
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [person({ discordUsername: "alice", spins: 1 })],
        smpPost: (url, body) => calls.push({ url, body }),
      }),
    )
    draw(<AccessPage />)

    await clickRowAction(/^spins$/i)
    const spins = within(await screen.findByRole("alertdialog")).getByLabelText(/^spins$/i)
    for (const refused of ["-1", "1.5", ""]) {
      fireEvent.change(spins, { target: { value: refused } })
      expect(asButton(screen.getByRole("button", { name: /^save$/i })).disabled).toBe(true)
    }
    fireEvent.change(spins, { target: { value: "4" } })
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }))

    await waitFor(() => expect(calls.length).toBe(1))
    expect(calls[0]).toEqual({ url: "/api/people/100000000000000001/spins", body: { spins: 4 } })
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

function badges(row: HTMLElement): HTMLElement {
  return assertElement(row.querySelector(":scope > td:first-child"), "the person and their badges")
}

function slots(row: HTMLElement): string[] {
  return [...row.querySelectorAll<HTMLElement>(":scope > td")].map((cell) =>
    [cell.getAttribute("data-phone"), cell.style.getPropertyValue("--phone-line")].filter(Boolean).join(" "),
  )
}

describe("AccessPage - the actions of a row depend on that row", () => {
  it("offers nothing to unlink for somebody with no Minecraft account", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).not.toContain("Unlink")
    expect(await actionsOf("Ally")).toContain("Unlink")
  })

  it("offers no Periods, which are on the person's page rather than behind a dialog", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("Ally")).not.toContain("Periods")
  })

  it("offers no Revoke where there is nothing running to take away", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).not.toContain("Revoke")
    expect(await actionsOf("Ally")).toContain("Revoke")
  })

  it("always offers Grant access, because more access can always be given", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    expect(await actionsOf("bob")).toContain("Grant access")
    expect(await actionsOf("Ally")).toContain("Grant access")
  })

  it("puts every row's actions behind one popover, however few there are, so the rows line up", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const ally = await rowFor("Ally")
    const bob = await rowFor("bob")
    expect(within(ally).getByRole("button", { name: /^Actions for/ })).toBeTruthy()
    expect(within(bob).getByRole("button", { name: /^Actions for/ })).toBeTruthy()
    expect(within(bob).queryByRole("button", { name: "Grant" })).toBeNull()
  })

  it("lays every phone card out in three lines: the person, access, and the three figures", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [
          person({ discordUsername: "quiet" }),
          person({
            discordId: "2",
            discordUsername: "player",
            accessActive: true,
            accessUntil: "2027-01-01T00:00:00Z",
            admin: true,
            minecraftUuid: "11111111-2222-3333-4444-555555555555",
            mcName: "PlayerMC",
            playtimeSeconds: 3_600,
            aura: 40,
            spins: 2,
          }),
        ],
      }),
    )
    draw(<AccessPage />)

    const card = ["wide 1", "start 2", "wide 3", "off", "off", "off", "action"]
    expect(slots(await rowFor("quiet"))).toEqual(card)
    expect(slots(await rowFor("player"))).toEqual(card)
    const figures = assertElement((await rowFor("player")).querySelector(":scope > td:nth-child(3)"), "the figures")
    expect(
      within(figures)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["1h", "40", "2"])
  })

  it("names a paying person's missing Minecraft link and a left guild beside the name", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [
          person({ discordUsername: "quiet" }),
          person({
            discordId: "2",
            discordUsername: "payer",
            memberState: "LEFT",
            accessActive: true,
            accessUntil: "2027-01-01T00:00:00Z",
          }),
        ],
      }),
    )
    draw(<AccessPage />)

    expect(badges(await rowFor("payer")).textContent).toContain("not linked")
    expect(badges(await rowFor("payer")).textContent).toContain("left")
    expect(badges(await rowFor("quiet")).textContent).not.toMatch(/not linked|left/)
  })

  it("says in words that a person never had access", async () => {
    vi.stubGlobal("fetch", backend({ people: () => [person({ discordUsername: "quiet" })] }))
    draw(<AccessPage />)

    const access = assertElement((await rowFor("quiet")).querySelector('td[data-label="Access"]'), "the access")
    expect(access.textContent).toBe("never had access")
  })

  it("keeps every action of the row on a phone's card, behind its one button", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    const actions = assertElement(
      within(await rowFor("Ally"))
        .getByRole("button", { name: /^Actions for/ })
        .closest("td"),
      "the actions cell",
    )
    expect(actions.getAttribute("data-phone")).toBe("action")
  })
})

describe("AccessPage - access changes are asked of the bot", () => {
  it("grants from a person's row with no id to type, and has no grant of its own at the top", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await rowFor("Ally")
    expect(screen.queryByRole("button", { name: "Grant access" })).toBeNull()
    const dialog = await openGrant()
    expect(within(dialog).getByText("Grant access to Ally")).toBeTruthy()
    expect(within(dialog).queryByRole("textbox")).toBeNull()
  })

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

  it("says the bot failed when it could not carry the change out", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        answer: (kind) => ({
          id: "a-grant",
          kind,
          status: "FAILED",
        }),
      }),
    )
    const failure = vi.spyOn(toast, "error")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(failure).toHaveBeenCalled())
    expect(toastDescription(failure.mock.calls[0][1]?.description)).toContain("The bot failed to do this.")
    failure.mockRestore()
  })

  it("says nothing changed when the bot never picked the request up", async () => {
    vi.stubGlobal("fetch", backend({ answer: (kind) => ({ id: "a-grant", kind, status: "EXPIRED" }) }))
    const failure = vi.spyOn(toast, "error")
    draw(<AccessPage />)

    const dialog = await openGrant()
    fireEvent.click(within(dialog).getByRole("button", { name: "Grant" }))

    await waitFor(() => expect(failure).toHaveBeenCalled())
    expect(toastDescription(failure.mock.calls[0][1]?.description)).toContain("Nothing changed")
    failure.mockRestore()
  })
})

describe("AccessPage - the generic command card is gone", () => {
  it("does not draw the 'Access commands' block any more", async () => {
    vi.stubGlobal("fetch", backend())
    draw(<AccessPage />)

    await screen.findAllByText("Ally")
    expect(screen.queryByText("Access commands")).toBeNull()
  })
})

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
    await screen.findAllByText("mine")
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

describe("AccessPage - the filters", () => {
  it("narrows the roster to admins and to people without a Minecraft link, and counts both on its button", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        people: () => [
          person({ discordId: "1", discordUsername: "quiet" }),
          person({ discordId: "2", discordUsername: "boss", admin: true }),
          person({
            discordId: "3",
            discordUsername: "linkedboss",
            admin: true,
            minecraftUuid: "11111111-2222-3333-4444-555555555555",
            mcName: "BossMC",
          }),
        ],
      }),
    )
    draw(<AccessPage />)
    await rowFor("quiet")

    fireEvent.click(screen.getByRole("button", { name: "Filters" }))
    fireEvent.click(screen.getByRole("button", { name: "Admins" }))
    await waitFor(() => expect(screen.queryByText("quiet")).toBeNull())
    await rowFor("linkedboss")

    fireEvent.click(screen.getByRole("button", { name: "Not linked" }))
    await waitFor(() => expect(screen.queryByText("linkedboss")).toBeNull())
    await rowFor("boss")
    expect(screen.getByRole("button", { name: "Filters 2" })).toBeTruthy()
  })
})
