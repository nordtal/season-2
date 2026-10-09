import { cleanup, fireEvent, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { PluginDescriptor } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { Settings, backend, draw, entry, location } from "@/components/steward/configuration.fixtures"

/** The sidebar: every group of the service as a row of its own. */
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

/** Nordtal's mark, the one `StewardMark` draws. */
const MARK = "/icon.png"

function group(name: string, service = "smp") {
  return {
    ...location({ service, path: `${service}/${name}`, name }),
    revision: "r1",
    restartRequired: false,
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
