import { cleanup, fireEvent, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { PluginDescriptor } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { Settings, backend, draw, entry, location } from "@/components/steward/configuration.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const MARK = "/icon.png"

function group(name: string, service = "smp", live = true) {
  return {
    ...location({ service, path: `${service}/${name}`, name, live }),
    revision: "r1",
    entries: [entry({ path: "enabled", key: "enabled", value: "true", type: "BOOLEAN" })],
  }
}

function smp(editors: Record<string, string> = {}): PluginDescriptor {
  return { service: "smp", id: "smp", editors }
}

function navLines(): string[] {
  const nav = screen.getByRole("navigation", { name: "Files" })
  return Array.from(nav.querySelectorAll("button")).map((node) => node.textContent?.trim() ?? "")
}

describe("the settings sidebar", () => {
  it("lists every group directly, named by the group and marked with Nordtal's mark", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": group("wheel"), "smp/milestones": group("milestones") }, [smp()]))

    draw(<Settings service="smp" />)

    await screen.findByRole("button", { name: "Wheel" })
    expect(navLines()).toEqual(["Milestones", "Wheel"])
    for (const name of ["Milestones", "Wheel"]) {
      const row = screen.getByRole("button", { name })
      expect(row.hasAttribute("aria-expanded")).toBe(false)
      expect(row.querySelector("img")?.getAttribute("src")).toBe(MARK)
    }
  })

  it("lists the live groups under Applies on save and the others under Applies after a restart", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "smp/wheel": group("wheel"),
        "smp/worlds": group("worlds", "smp", false),
        "smp/border": group("border"),
      }),
    )

    draw(<Settings service="smp" />)

    await screen.findByRole("button", { name: "Wheel" })
    const nav = screen.getByRole("navigation", { name: "Files" })
    expect(
      Array.from(nav.querySelectorAll("h2, button")).map((node) => `${node.tagName} ${node.textContent?.trim()}`),
    ).toEqual(["H2 Applies on save", "BUTTON Border", "BUTTON Wheel", "H2 Applies after a restart", "BUTTON Worlds"])
  })

  it("shows no restart note on a group that applies after a restart", async () => {
    vi.stubGlobal("fetch", backend({ "smp/worlds": group("worlds", "smp", false) }))

    draw(<Settings service="smp" />)
    fireEvent.click(await screen.findByRole("button", { name: "Worlds" }))

    await screen.findByLabelText("Search this file")
    expect(screen.queryByText("Applies after a restart.")).toBeNull()
  })

  it("lists the groups the same way when no descriptor answers", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": group("wheel"), "smp/milestones": group("milestones") }))

    draw(<Settings service="smp" />)

    await screen.findByRole("button", { name: "Wheel" })
    expect(navLines()).toEqual(["Milestones", "Wheel"])
    expect(screen.getByRole("button", { name: "Wheel" }).querySelector("img")?.getAttribute("src")).toBe(MARK)
  })

  it("draws a group whose editor this page does not know with the form from its schema", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ "smp/wheel": group("wheel"), "smp/milestones": group("milestones") }, [
        smp({ wheel: "an-editor-from-a-newer-jar" }),
      ]),
    )

    draw(<Settings service="smp" />)
    fireEvent.click(await screen.findByRole("button", { name: "Wheel" }))

    expect(await screen.findByLabelText("Search this file")).toBeTruthy()
  })
})
