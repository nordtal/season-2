import { cleanup, fireEvent, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import type { ConfigEntry } from "@/lib/api"
import { Settings, asInput, backend, draw, entry, location, open } from "@/components/steward/configuration.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const FILE = "smp/example"

function document(entries: ConfigEntry[]) {
  return { [FILE]: { ...location({ path: FILE, name: "example", service: "smp" }), revision: "r1", entries } }
}

function asButton(element: HTMLElement): HTMLButtonElement {
  if (!(element instanceof HTMLButtonElement)) throw new Error("expected a button")
  return element
}

const days = entry({ path: "days", key: "days", label: "Days", type: "INTEGER", rules: [{ rule: "POSITIVE" }] })
const price = entry({ path: "price-cents", key: "price-cents", label: "Price", type: "INTEGER" })

function tier(index: number, day: string, cents: string): ConfigEntry[] {
  return [
    { ...days, path: `tiers[${index}].days`, value: day },
    { ...price, path: `tiers[${index}].price-cents`, value: cents },
  ]
}

const tiers = entry({
  path: "tiers",
  key: "tiers",
  label: "Tiers",
  kind: "SECTIONS",
  rules: [{ rule: "UNIQUE", field: "days" }],
  template: [days, price],
  sections: [tier(0, "30", "500"), tier(1, "90", "900")],
})

describe("a field checked by the rules its setting declares", () => {
  it("names the rule a typed value breaks and keeps Save from sending it", async () => {
    vi.stubGlobal(
      "fetch",
      backend(
        document([
          entry({
            path: "port",
            key: "port",
            label: "Port",
            type: "INTEGER",
            value: "25565",
            rules: [{ rule: "POSITIVE" }, { rule: "AT_MOST", limit: 65535 }],
          }),
        ]),
      ),
    )
    draw(<Settings service="smp" />)
    await open("Example")

    const field = asInput(await screen.findByLabelText("Port"))
    fireEvent.change(field, { target: { value: "0" } })

    expect(field.getAttribute("aria-invalid")).toBe("true")
    expect(screen.getByText("Port must be greater than zero.")).toBeTruthy()
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(true)

    fireEvent.change(field, { target: { value: "70000" } })
    expect(screen.getByText("Port must be at most 65535.")).toBeTruthy()

    fireEvent.change(field, { target: { value: "25566" } })
    expect(field.getAttribute("aria-invalid")).toBe("false")
    expect(asButton(screen.getByRole("button", { name: "Save 1" })).disabled).toBe(false)
  })

  it("marks the entry of a list that breaks a rule, by its index", async () => {
    vi.stubGlobal("fetch", backend(document([tiers])))
    const { container } = draw(<Settings service="smp" />)
    await open("Example")

    const second = await screen.findByDisplayValue("90")
    fireEvent.change(second, { target: { value: "30" } })

    expect(second.getAttribute("aria-invalid")).toBe("true")
    expect(second.id).toBe("tiers.1.days")
    expect(screen.getByText("Days holds 30 twice.")).toBeTruthy()
    expect(container.querySelector("#tiers\\.0\\.days")?.getAttribute("aria-invalid")).toBe("false")

    fireEvent.change(second, { target: { value: "0" } })
    expect(screen.getByText("Days must be greater than zero.")).toBeTruthy()
  })

  it("marks the field a refused save names", async () => {
    const answers = backend(
      document([entry({ path: "motd", key: "motd", label: "Message of the day", value: "Hello" })]),
    )
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      if (init?.method !== "PUT") return answers(url, init)
      return new Response(
        JSON.stringify({
          error:
            "Not saved. smp would not start with this change. motd names Nordtal, which this server does not have.",
          code: "SETTINGS_REFUSED",
          refusal: { setting: "motd", entries: [], rule: "KNOWN", value: "Nordtal" },
        }),
        { status: 422, headers: { "Content-Type": "application/json" } },
      )
    })
    draw(<Settings service="smp" />)
    await open("Example")

    const field = asInput(await screen.findByLabelText("Message of the day"))
    fireEvent.change(field, { target: { value: "Welcome to Nordtal" } })
    fireEvent.click(screen.getByRole("button", { name: "Save 1" }))

    expect(await screen.findByText("Message of the day names Nordtal, which this server does not have.")).toBeTruthy()
    expect(field.getAttribute("aria-invalid")).toBe("true")

    fireEvent.change(field, { target: { value: "Welcome" } })
    expect(field.getAttribute("aria-invalid")).toBe("false")
  })
})
