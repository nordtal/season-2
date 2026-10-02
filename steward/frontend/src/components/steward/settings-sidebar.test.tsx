import { cleanup, fireEvent, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { PluginDescriptor } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { Settings, backend, draw, entry, location } from "@/components/steward/configuration.fixtures"

/** The sidebar grouped by what each plugin's descriptor says of itself. */
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const LOGO = "data:image/png;base64,iVBORw=="

function group(name: string, service = "smp") {
  return {
    ...location({ service, path: `${service}/${name}`, name }),
    revision: "r1",
    restartRequired: false,
    entries: [entry({ path: "enabled", key: "enabled", value: "true", type: "BOOLEAN" })],
  }
}

function smp(editors: Record<string, string> = {}): PluginDescriptor {
  return { service: "smp", id: "smp", name: "SMP", logo: LOGO, editors }
}

function navLines(): string[] {
  const nav = screen.getByRole("navigation", { name: "Files" })
  return Array.from(nav.querySelectorAll("button")).map((node) => node.textContent?.trim() ?? "")
}

describe("the settings sidebar", () => {
  it("folds a plugin with several groups into one row with its logo, closed until opened", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": group("wheel"), "smp/milestones": group("milestones") }, [smp()]))

    draw(<Settings service="smp" />)

    const plugin = await screen.findByRole("button", { name: "SMP" })
    expect(plugin.getAttribute("aria-expanded")).toBe("false")
    expect(plugin.querySelector("img")?.getAttribute("src")).toBe(LOGO)
    expect(navLines()).toEqual(["SMP"])

    fireEvent.click(plugin)

    expect(plugin.getAttribute("aria-expanded")).toBe("true")
    expect(navLines()).toEqual(["SMP", "Milestones", "Wheel"])
    /** The groups inside carry the gear, not the logo again. */
    expect(screen.getByRole("button", { name: "Wheel" }).querySelector("img")).toBeNull()
  })

  it("keeps a plugin's single group as its row, with the logo for the gear", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": group("wheel") }, [smp()]))

    draw(<Settings service="smp" />)

    const row = await screen.findByRole("button", { name: "Wheel" })
    expect(row.querySelector("img")?.getAttribute("src")).toBe(LOGO)
    expect(row.hasAttribute("aria-expanded")).toBe(false)
  })

  it("lists the groups flat when no descriptor claims them", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": group("wheel"), "smp/milestones": group("milestones") }))

    draw(<Settings service="smp" />)

    await screen.findByRole("button", { name: "Wheel" })
    expect(navLines()).toEqual(["Milestones", "Wheel"])
  })

  it("draws a group whose editor this page does not know with the form from its schema", async () => {
    vi.stubGlobal(
      "fetch",
      backend({ "smp/wheel": group("wheel"), "smp/milestones": group("milestones") }, [
        smp({ wheel: "an-editor-from-a-newer-jar" }),
      ]),
    )

    draw(<Settings service="smp" />)
    fireEvent.click(await screen.findByRole("button", { name: "SMP" }))
    fireEvent.click(screen.getByRole("button", { name: "Wheel" }))

    expect(await screen.findByLabelText("Search this file")).toBeTruthy()
  })
})
