import { EditorView } from "@codemirror/view"
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { PluginFile, PluginFileView } from "@/lib/api"
import { resetDrafts } from "@/lib/drafts"
import { Settings, backend, draw, entry, location } from "@/components/steward/configuration.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

const CORE_PROTECT_ICON = "https://cdn.example/core-protect.png"

const WHEEL = {
  ...location({ service: "smp", path: "smp/wheel", name: "wheel" }),
  revision: "r1",
  entries: [entry({ path: "enabled", key: "enabled", value: "true", type: "BOOLEAN" })],
}

function file(plugin: string, path: string, over: Partial<PluginFile> = {}): PluginFile {
  return { service: "smp", plugin, name: plugin, path, size: 10, readable: true, writable: true, ...over }
}

function view(of: PluginFile, text: string, entries?: PluginFileView["entries"]): PluginFileView {
  return { file: of, text, revision: `rev-${of.path}`, entries }
}

/** A single-file plugin, a plugin with a nested folder, one without an icon and a folder no plugin claims. */
const PLUGINS: Record<string, PluginFileView> = {
  "CoreProtect/config.yml": view(
    file("CoreProtect", "config.yml", { iconUrl: CORE_PROTECT_ICON }),
    "use-mysql: false\n",
    [entry({ path: "use-mysql", key: "use-mysql", label: "use-mysql", value: "false", type: "BOOLEAN" })],
  ),
  "LuckPerms/config.yml": view(file("LuckPerms", "config.yml"), "server: global\n"),
  "LuckPerms/yaml-storage/groups/default.yml": view(
    file("LuckPerms", "yaml-storage/groups/default.yml"),
    "permissions:\n  - permission: a\n",
  ),
  "Chunky/config.yml": view(file("Chunky", "config.yml"), "language: en\n"),
  "bStats/config.yml": view(file("bStats", "config.yml", { name: undefined }), "enabled: true\n"),
  "bStats/notes.txt": view(file("bStats", "notes.txt", { name: undefined }), "notes\n"),
}

/** Replaces the editor's whole text, as typing would. */
function type(text: HTMLElement, value: string) {
  const editor = EditorView.findFromDOM(text)!
  editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
}

function navRows(): string[] {
  const nav = screen.getByRole("navigation", { name: "Files" })
  return Array.from(nav.querySelectorAll("button")).map((node) => node.textContent?.trim() ?? "")
}

describe("the plugins' files on the settings page", () => {
  it("lists them as their own restart-only group below Nordtal's groups, sorted by plugin, a plugin with several files as a closed tree", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": WHEEL }, [], PLUGINS))

    draw(<Settings service="smp" />)

    await screen.findByRole("button", { name: "CoreProtect" })
    expect(navRows()).toEqual(["Wheel", "B stats", "Chunky", "CoreProtect", "LuckPerms"])
    const nav = screen.getByRole("navigation", { name: "Files" })
    expect(Array.from(nav.querySelectorAll("h2")).map((node) => node.textContent)).toEqual([
      "Applies on save",
      "Plugin files",
    ])
    // One file: one row and no caret. Several: a branch, closed until opened.
    expect(screen.getByRole("button", { name: "CoreProtect" }).hasAttribute("aria-expanded")).toBe(false)
    expect(screen.getByRole("button", { name: "CoreProtect" }).querySelector("img")?.getAttribute("src")).toBe(
      CORE_PROTECT_ICON,
    )
    expect(screen.getByRole("button", { name: "Chunky" }).querySelector("img")).toBeNull()
    const luckPerms = screen.getByRole("button", { name: "LuckPerms" })
    expect(luckPerms.getAttribute("aria-expanded")).toBe("false")

    // The plugin's icon leads its row like a file's, and the caret follows the name.
    expect(luckPerms.lastElementChild?.previousElementSibling?.textContent).toBe("LuckPerms")

    fireEvent.click(luckPerms)
    fireEvent.click(screen.getByRole("button", { name: /yaml-storage/ }))
    expect(
      screen.getByRole("button", { name: /yaml-storage/ }).lastElementChild?.previousElementSibling?.textContent,
    ).toBe("yaml-storagegroups")

    expect(navRows()).toEqual([
      "Wheel",
      "B stats",
      "Chunky",
      "CoreProtect",
      "LuckPerms",
      "config.yml",
      // A folder with only a folder in it is one row with both names.
      "yaml-storagegroups",
      "default.yml",
    ])
  })

  it("opens a file that Steward can rewrite as a form, and any other as its text with a draft", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": WHEEL }, [], PLUGINS))

    draw(<Settings service="smp" />)
    fireEvent.click(await screen.findByRole("button", { name: "CoreProtect" }))
    expect(await screen.findByLabelText("Search this file")).toBeTruthy()
    expect(screen.getByText("use-mysql")).toBeTruthy()
    // The sidebar's group says when it applies; the file does not say it again.
    expect(screen.queryByText("Applies after a restart.")).toBeNull()

    fireEvent.click(screen.getByRole("button", { name: "Chunky" }))
    const text = await screen.findByRole("textbox", { name: "Chunky config.yml" })
    expect(text.textContent).toBe("language: en")
    expect(screen.queryByText("Applies after a restart.")).toBeNull()
    type(text, "language: de\n")

    expect(await screen.findByRole("button", { name: "Save 1" })).toBeTruthy()
    const chunky = screen.getByRole("button", { name: /Chunky/ })
    expect(within(chunky).getByLabelText("Changed")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "Discard" }))
    await waitFor(() => expect(text.textContent).toBe("language: en"))
  })

  it("colours a YAML file's text by its syntax and marks where it stops parsing", async () => {
    vi.stubGlobal("fetch", backend({ "smp/wheel": WHEEL }, [], PLUGINS))

    draw(<Settings service="smp" />)
    fireEvent.click(await screen.findByRole("button", { name: "Chunky" }))
    const text = await screen.findByRole("textbox", { name: "Chunky config.yml" })
    expect(within(text).getByText("language").className).not.toBe("")

    type(text, "language: [en\nother: 1\n")
    // The linter waits for typing to pause; the gutter marks the line whether the error is a range or a point.
    await waitFor(() => expect(document.querySelector(".cm-lint-marker-error")).toBeTruthy(), { timeout: 3000 })
  })
})
