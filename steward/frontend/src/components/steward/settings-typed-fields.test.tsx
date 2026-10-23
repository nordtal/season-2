import { cleanup, fireEvent, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import type { ConfigEntry } from "@/lib/api"
import { problemOf } from "@/components/steward/config-controls"
import { Settings, asInput, backend, draw, entry, location, open } from "@/components/steward/configuration.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const FILE = "smp/example"

function withEntries(entries: ConfigEntry[]) {
  vi.stubGlobal(
    "fetch",
    backend({ [FILE]: { ...location({ path: FILE, name: "example", service: "smp" }), revision: "r1", entries } }),
  )
}

describe("a field typed by the value it holds", () => {
  it("says what a whole number takes, in words, and keeps Save from sending it", async () => {
    withEntries([
      entry({ path: "max-players", key: "max-players", label: "Max players", type: "INTEGER", value: "20" }),
    ])
    draw(<Settings service="smp" />)
    await open("Example")

    const field = asInput(await screen.findByLabelText("Max players"))
    fireEvent.change(field, { target: { value: "2O" } })

    expect(field.getAttribute("aria-invalid")).toBe("true")
    expect(screen.getByText("Takes a whole number, such as 12.")).toBeTruthy()
    expect(screen.getByText("One field holds a value it cannot take.")).toBeTruthy()
    expect(asButton(await screen.findByRole("button", { name: "Save 1" })).disabled).toBe(true)

    fireEvent.change(field, { target: { value: "30" } })

    expect(field.getAttribute("aria-invalid")).toBe("false")
    expect(screen.queryByText("Takes a whole number, such as 12.")).toBeNull()
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(false)
  })

  it("marks each list item on its own", async () => {
    withEntries([
      entry({ path: "rates", key: "rates", label: "Rates", kind: "LIST", type: "DECIMAL", items: ["0.5", "2"] }),
    ])
    draw(<Settings service="smp" />)
    await open("Example")

    fireEvent.change(await screen.findByLabelText("Entry 2"), { target: { value: "2,5" } })

    expect(screen.getByLabelText("Entry 1").getAttribute("aria-invalid")).toBe("false")
    expect(screen.getByLabelText("Entry 2").getAttribute("aria-invalid")).toBe("true")
    expect(screen.getByText("Takes a number, such as 2.5.")).toBeTruthy()
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(true)
  })

  it("marks a card's field that shows its default because the stored card lacks it", async () => {
    const spins = entry({ path: "spins", key: "spins", label: "Spins", type: "INTEGER", value: "1" })
    const name = entry({ path: "name", key: "name", label: "Name" })
    withEntries([
      entry({
        path: "awards",
        key: "awards",
        label: "Awards",
        kind: "SECTIONS",
        template: [name, spins],
        sections: [
          [
            { ...name, path: "awards[0].name", value: "first" },
            { ...spins, path: "awards[0].spins", filled: false },
          ],
        ],
      }),
    ])
    draw(<Settings service="smp" />)
    await open("Example")

    expect(await screen.findByText("The default. It is not saved yet.")).toBeTruthy()
    expect(screen.queryByRole("button", { name: /Save/ })).toBeNull()
  })
})

function typed(type: ConfigEntry["type"], refers?: ConfigEntry["refers"]) {
  return entry({ path: "value", key: "value", type, refers })
}

describe("the check by type", () => {
  it("takes what the server converts and refuses the rest", () => {
    expect(problemOf(typed("INTEGER"), " -12 ")).toBeNull()
    expect(problemOf(typed("INTEGER"), "9223372036854775808")).not.toBeNull()
    expect(problemOf(typed("INTEGER"), "1.5")).not.toBeNull()
    expect(problemOf(typed("DECIMAL"), "1e3")).toBeNull()
    expect(problemOf(typed("DECIMAL"), ".5")).toBeNull()
    expect(problemOf(typed("DECIMAL"), "")).not.toBeNull()
    expect(problemOf(typed("BOOLEAN"), "yes")).not.toBeNull()
  })

  it("takes a game's key with or without its namespace, and a tag", () => {
    const item = typed("STRING", { to: "ITEM", optional: false })
    expect(problemOf(item, "minecraft:oak_log")).toBeNull()
    expect(problemOf(item, "oak_log")).toBeNull()
    expect(problemOf(item, "#minecraft:logs")).toBeNull()
    expect(problemOf(item, "")).toBeNull()
    expect(problemOf(item, "Oak Log")).toBe(
      "Takes a key such as minecraft:oak_log, in lower-case letters, digits, underscores, hyphens, dots and slashes.",
    )
    expect(problemOf(typed("STRING", { to: "DISCORD_CHANNEL", optional: false }), "Oak Log")).toBeNull()
  })
})

function asButton(element: HTMLElement): HTMLButtonElement {
  if (!(element instanceof HTMLButtonElement)) throw new Error("expected a button")
  return element
}
