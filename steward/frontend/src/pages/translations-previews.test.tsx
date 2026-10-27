import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import { PLACES, backend, draw, listing, openItem, Page, source, text } from "@/pages/translations.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  resetDrafts()
})

/**
 * A key shown in three places, two of which a preview reaches, and a page text that no preview reaches.
 */
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
            args: [{ name: "player", kind: "name", global: false, example: "Alex", action: false }],
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
          "smp:7": { id: "smp:7", status: "DONE" },
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
    await openItem("Page", /^Steward and admin/)
    expect(screen.getAllByRole("figure").map((figure) => figure.getAttribute("aria-label"))).toEqual(["Steward"])
    expect(screen.queryByRole("button", { name: /in game|in Discord/ })).toBeNull()

    await openItem("Welcome")
    expect(screen.getAllByRole("figure").map((figure) => figure.getAttribute("aria-label"))).toEqual([
      "Chat",
      "Discord message",
      "Steward",
    ])
    expect(within(screen.getByRole("figure", { name: "Steward" })).queryByRole("button")).toBeNull()
    fireEvent.change(await source("Welcome"), { target: { value: "Moin {player}" } })
    expect(screen.getByText("9/2000")).toBeTruthy()

    const chat = screen.getByRole("figure", { name: "Chat" })
    const show = within(chat).getByRole("button", { name: "Show it to my player in game" })
    fireEvent.click(show)
    await waitFor(() => expect(within(show).getByRole("status").textContent).toBe("Done"))
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

  it("draws the bot in every Discord message as Nordtal's mark, round like a Discord avatar", async () => {
    const discord = ["DISCORD_MESSAGE", "DISCORD_EMBED", "DISCORD_BUTTON", "DISCORD_SELECT", "DISCORD_MODAL"]
    vi.stubGlobal(
      "fetch",
      backend(
        listing([
          text(
            { texts: { en: ["Moin"] }, key: "moin", name: "Moin", shown: discord },
            { previews: Object.fromEntries(discord.map((place) => [place, "DISCORD"])) },
          ),
        ]),
      ),
    )
    draw(<Page />)
    await openItem("Moin", /^Discord/)

    const avatars = (await screen.findAllByRole("figure")).map((figure) => {
      const avatar = figure.querySelector("img")
      return [figure.getAttribute("aria-label"), avatar?.getAttribute("src"), avatar?.style.borderRadius]
    })
    expect(avatars).toEqual([
      ["Discord message", "/icon.png", "50%"],
      ["Discord embed", "/icon.png", "50%"],
      ["Discord button", "/icon.png", "50%"],
      ["Discord menu", "/icon.png", "50%"],
      ["Discord form", undefined, undefined],
    ])
  })

  it("asks for the filtered service's palette", async () => {
    const bodies: unknown[] = []
    threePlaces(bodies)
    draw(<Page service="smp" />)
    await openItem("Welcome")

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
            args: [{ name: "state", kind: "choice", global: false, example: "true", action: false }],
          }),
          text({
            texts: { en: ["{count, plural, one {# player} other {# players}} online"] },
            key: "online",
            name: "Online",
            shown: ["STEWARD"],
            args: [
              {
                name: "count",
                kind: "number",
                global: false,
                example: "1",
                action: false,
                exampleValue: { kind: "number", value: 1 },
              },
            ],
          }),
        ]),
      ),
    )
    draw(<Page />)
    await openItem("Service state", /^Steward/)
    expect(within(await screen.findByRole("figure", { name: "Steward" })).getByText("Stopped")).toBeTruthy()

    await openItem("Online", /^Steward/)
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
    await openItem("Yes", /^Building blocks/)

    await screen.findByRole("textbox", { name: "Yes" })
    expect(screen.getAllByRole("figure")).toHaveLength(1)
    expect(screen.queryByRole("button", { name: /in game|in Discord/ })).toBeNull()
  })

  it("shows a nested message as its words in the edited language, and sends the server its key", async () => {
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
                  exampleValue: { kind: "message", value: { key: "restart.what.network", args: {} } },
                },
              ],
            },
            { previews: { CHAT: "GAME" } },
          ),
          text({ texts: { en: ["The network"] }, key: "restart.what.network", name: "Network", bundle: "restart" }),
        ]),
        undefined,
        {
          preview: (body) => {
            bodies.push(body)
            return "smp:3"
          },
          commands: {
            "smp:3": { id: "smp:3", status: "DONE" },
          },
        },
      ),
    )
    draw(<Page />)
    const item = await openItem("Restart notice")

    expect(await within(item).findByText("The network")).toBeTruthy()
    expect(screen.queryByText("restart.what.network")).toBeNull()
    const show = screen.getByRole("button", { name: "Show it to my player in game" })
    fireEvent.click(show)

    await waitFor(() => expect(within(show).getByRole("status").textContent).toBe("Done"))
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
