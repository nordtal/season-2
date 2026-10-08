import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import { words } from "@/lib/query-fixtures"
import { PLACES, backend, draw, listing, open, openKey, Page, source, text } from "@/pages/texts.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  resetDrafts()
})

/** A key shown in three places, two of which a preview reaches, and a page text no preview reaches. */
function threePlaces(sent: unknown[]) {
  vi.stubGlobal(
    "fetch",
    backend(
      listing([
        text(
          {
            texts: { en: ["Welcome {player}"] },
            key: "welcome",
            name: "Welcome",
            shown: ["CHAT", "DISCORD_MESSAGE", "STEWARD"],
            limit: 2000,
            args: [{ name: "player", kind: "name", global: false, example: "Alex", action: false, exampleWords: {} }],
          },
          { previews: { CHAT: "GAME", DISCORD_MESSAGE: "DISCORD" } },
        ),
        text({ texts: { en: ["Page"] }, key: "page", name: "Page", shown: ["STEWARD"] }),
      ]),
      undefined,
      {
        preview: (body) => {
          sent.push(body)
          return "smp:7"
        },
        commands: {
          "smp:7": { id: "smp:7", status: "DONE", result: words("Shown to your player in game.") },
        },
      },
    ),
  )
}

describe("the previews", () => {
  it("draws one per place, and sends the text being typed to the admin as the place it is sent from", async () => {
    const bodies: unknown[] = []
    threePlaces(bodies)
    draw(<Page />)
    await open(/^In game/)
    await open(/^Steward & Admin/)
    await openKey("Page")
    expect(screen.getAllByRole("figure").map((figure) => figure.getAttribute("aria-label"))).toEqual(["Steward"])
    expect(screen.queryByRole("button", { name: /in game|in Discord/ })).toBeNull()

    await openKey("Welcome")
    expect(screen.getAllByRole("figure").map((figure) => figure.getAttribute("aria-label"))).toEqual([
      "Chat",
      "Discord message",
      "Steward",
    ])
    expect(within(screen.getByRole("figure", { name: "Steward" })).queryByRole("button")).toBeNull()
    fireEvent.change(await source("Welcome"), { target: { value: "Moin {player}" } })
    expect(screen.getByText("9/2000")).toBeTruthy()

    const chat = screen.getByRole("figure", { name: "Chat" })
    fireEvent.click(within(chat).getByRole("button", { name: "Show it to my player in game" }))
    await within(chat).findByText("Shown to your player in game.")
    fireEvent.click(
      within(screen.getByRole("figure", { name: "Discord message" })).getByRole("button", {
        name: "Send it to me in Discord",
      }),
    )

    await waitFor(() => expect(bodies).toHaveLength(2))
    const sent = { bundle: "smp/smp", key: "welcome", language: "en", text: "Moin {player}" }
    expect(bodies).toEqual([
      { ...sent, shown: "CHAT", values: { player: "Alex" } },
      { ...sent, shown: "DISCORD_MESSAGE", values: { player: "Alex" } },
    ])
  })

  it("asks for the filtered service's palette", async () => {
    const bodies: unknown[] = []
    threePlaces(bodies)
    draw(<Page service="smp" />)
    await open(/^In game/)
    await openKey("Welcome")

    fireEvent.click(
      within(await screen.findByRole("figure", { name: "Chat" })).getByRole("button", {
        name: "Show it to my player in game",
      }),
    )

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({ shown: "CHAT", service: "smp" })
  })

  it("draws the case of a plural or a choice that the example picks", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text({
            texts: { en: ["{state, select, RUNNING {Running} other {Stopped}}"] },
            key: "state",
            name: "Service state",
            shown: ["STEWARD"],
            args: [{ name: "state", kind: "choice", global: false, example: "true", action: false, exampleWords: {} }],
          }),
          text({
            texts: { en: ["{count, plural, one {# player} other {# players}} online"] },
            key: "online",
            name: "Online",
            shown: ["STEWARD"],
            args: [{ name: "count", kind: "number", global: false, example: "1", action: false, exampleWords: {} }],
          }),
        ]),
      ),
    )
    draw(<Page />)
    await openKey("Service state")
    expect(within(await screen.findByRole("figure", { name: "Steward" })).getByText("Stopped")).toBeTruthy()

    await openKey("Online")
    // The figure's text begins with its caption, the place's name.
    expect(screen.getByRole("figure", { name: "Steward" }).textContent).toBe("Steward1 player online")
  })

  it("draws a building block once, plain, though every place shows it", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text(
            { texts: { en: ["yes"] }, key: "yes", bundle: "values", name: "Yes", shown: Object.keys(PLACES) },
            { previews: { CHAT: "GAME", DISCORD_MESSAGE: "DISCORD" } },
          ),
        ]),
      ),
    )
    draw(<Page />)
    await openKey("Yes")

    await screen.findByRole("textbox", { name: "Yes" })
    expect(screen.getAllByRole("figure")).toHaveLength(1)
    expect(screen.queryByRole("button", { name: /in game|in Discord/ })).toBeNull()
  })

  it("shows a nested message as its words, and sends the server its key", async () => {
    const bodies: unknown[] = []
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text(
            {
              texts: { en: ["{what} restarts"] },
              key: "restart.notice",
              name: "Restart notice",
              shown: ["CHAT"],
              args: [
                {
                  name: "what",
                  kind: "message",
                  global: false,
                  example: "restart.what.network",
                  action: false,
                  exampleWords: { en: "The network" },
                },
              ],
            },
            { previews: { CHAT: "GAME" } },
          ),
        ]),
        undefined,
        {
          preview: (body) => {
            bodies.push(body)
            return "smp:3"
          },
          commands: {
            "smp:3": { id: "smp:3", status: "DONE", result: words("Shown to your player in game.") },
          },
        },
      ),
    )
    draw(<Page />)
    await openKey("Restart notice")

    expect(await screen.findByText("The network")).toBeTruthy()
    expect(screen.queryByText("restart.what.network")).toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Show it to my player in game" }))

    await screen.findByText("Shown to your player in game.")
    expect(bodies).toEqual([
      {
        bundle: "smp/smp",
        key: "restart.notice",
        language: "en",
        text: "{what} restarts",
        shown: "CHAT",
        values: { what: "restart.what.network" },
      },
    ])
  })
})
