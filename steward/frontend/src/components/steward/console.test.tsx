import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { CommandNode } from "@/lib/api"
import { keys } from "@/lib/query-keys"
import { ServiceConsole, offeredSteps } from "./console"

/** The Console window: newest line on top, levels as colours, stack traces, run lines, Find, steps and failures. */

type Listener = (event: Event) => void

class FakeEventSource {
  static opened: FakeEventSource[] = []
  onerror: Listener | null = null
  private readonly listeners = new Map<string, Listener[]>()
  readonly url: string
  constructor(url: string) {
    this.url = url
    FakeEventSource.opened.push(this)
  }
  addEventListener(type: string, listener: Listener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }
  close() {}
  emit(type: string, data?: string) {
    const event = data === undefined ? new Event(type) : new MessageEvent(type, { data })
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
}

const live = () => FakeEventSource.opened.at(-1)!

function mount() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ServiceConsole name="smp" hasConsole={false} capacity={10000} />
    </QueryClientProvider>,
  )
  act(() => live().emit("open"))
}

/** The console line of a server whose published tree is already in the cache, so nothing is fetched. */
function mountLine(nodes: CommandNode[]) {
  const client = new QueryClient()
  client.setQueryData(keys.commandTree("smp"), { nodes })
  render(
    <QueryClientProvider client={client}>
      <ServiceConsole name="smp" hasConsole capacity={10000} />
    </QueryClientProvider>,
  )
  act(() => live().emit("open"))
  return screen.getByRole<HTMLInputElement>("combobox", { name: "Send a line to the server console" })
}

const TREE: CommandNode[] = [
  { name: "", children: [1, 2, 3, 4] },
  { name: "execute", children: [5] },
  { name: "give", children: [6] },
  { name: "say", children: [7] },
  { name: "stop", executes: true },
  { name: "run", redirect: 0 },
  { name: "targets", argument: true, children: [8] },
  { name: "message", argument: true, executes: true },
  { name: "item", argument: true, executes: true },
]

const shown = () => screen.queryAllByRole("option").map((option) => option.textContent)

function feed(...events: Array<[string, string]>) {
  act(() => {
    for (const [type, data] of events) live().emit(type, data)
  })
  act(() => {
    vi.advanceTimersByTime(20)
  })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "requestAnimationFrame", "cancelAnimationFrame"] })
  FakeEventSource.opened = []
  vi.stubGlobal("EventSource", FakeEventSource)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe("ServiceConsole", () => {
  it("draws the newest line on top, with the run and end lines in their place", () => {
    mount()
    feed(
      ["end", "Nothing older."],
      ["run", "Earlier run, 22 Sep 19:44"],
      ["line", "[19:44:30] [Server thread/INFO]: [smp] first"],
      ["line", "[19:44:31] [Server thread/INFO]: [smp] second"],
    )
    const texts = [...document.querySelectorAll("section[aria-label=Console] .grid > div")].map(
      (row) => row.textContent,
    )
    expect(texts).toEqual(["19:44:31smpsecond", "19:44:30smpfirst", "Earlier run, 22 Sep 19:44", "Nothing older."])
  })

  it("colours the text by level and lets a stack trace keep its error's colour", () => {
    mount()
    feed(
      ["line", "[10:00:00] [Server thread/ERROR]: [smp] it broke"],
      ["line", "\tat eu.nordtal.s2.smp.Smp.onEnable(Smp.java:42)"],
      ["line", "[10:00:01] [Server thread/WARN]: Can't keep up!"],
      ["line", "There are 0 of a max of 40 players online:"],
    )
    expect(screen.getByText("it broke").className).toContain("text-red-400")
    expect(screen.getByText(/Smp\.onEnable/).className).toContain("text-red-400")
    expect(screen.getByText("Can't keep up!").className).toContain("text-amber-300")
    expect(screen.getByText(/players online/).className).toContain("text-white/85")
    // The thread falls away; the time loses nothing but stays apart from the text.
    expect(screen.queryByText(/Server thread/)).toBeNull()
  })

  it("puts a continuation line under the message column, also when its head fell out of the window", () => {
    mount()
    feed(
      ["line", " - DisplayTags (2.2.0), smp (0.9.5)"],
      ["line", "[21:40:36] [ServerMain/INFO]: [PluginInitializerManager] Bukkit plugins (3):"],
      ["line", " - Chunky (1.5.3), voicechat (2.6.24)"],
    )
    const orphan = screen.getByText(/DisplayTags/)
    const carried = screen.getByText(/Chunky/)
    expect(orphan.className).toContain("sm:col-start-3")
    expect(carried.className).toContain("sm:col-start-3")
    expect(orphan.className).not.toContain("col-span-full")
    // A line that carries on nothing keeps the whole width.
    feed(["line", "There are 0 of a max of 40 players online:"])
    expect(screen.getByText(/players online/).className).toContain("col-span-full")
  })

  it("filters with Find and counts what it found, and Escape gives everything back", () => {
    mount()
    feed(["line", "[10:00:00] [Server thread/INFO]: alpha"], ["line", "[10:00:01] [Server thread/INFO]: beta"])
    fireEvent.click(screen.getByRole("button", { name: "Find" }))
    const field = screen.getByRole("textbox", { name: "Find in the console" })
    fireEvent.change(field, { target: { value: "BET" } })
    expect(screen.queryByText("alpha")).toBeNull()
    expect(screen.getByText("beta")).toBeTruthy()
    expect(screen.getByText("1")).toBeTruthy()
    fireEvent.keyDown(field, { key: "Escape" })
    expect(screen.getByText("alpha")).toBeTruthy()
  })

  it("draws the failure where the lines would be, not above the window", () => {
    mount()
    feed(["line", "[10:00:00] [Server thread/INFO]: alpha"])
    for (let attempt = 1; attempt <= 5; attempt++) {
      act(() => live().emit("gone", "no running container for smp"))
      act(() => {
        vi.advanceTimersByTime(30_000)
      })
    }
    expect(screen.getByRole("alert").textContent).toContain("Can't reach the log.")
    expect(screen.getByRole("alert").textContent).toContain("no running container for smp")
    expect(screen.queryByText("alpha")).toBeNull()
  })

  it("suggests from the server's own tree as a word is typed, and a tap or Tab takes it", () => {
    const line = mountLine(TREE)
    expect(screen.queryByRole("listbox")).toBeNull()

    fireEvent.change(line, { target: { value: "gi" } })
    expect(shown()).toEqual(["give"])
    fireEvent.click(screen.getByRole("option", { name: "give" }))
    expect(line.value).toBe("give ")
    expect(shown()).toEqual(["<targets>"])
    expect(screen.getByRole("option", { name: "<targets>" }).getAttribute("aria-disabled")).toBe("true")

    fireEvent.change(line, { target: { value: "execute r" } })
    fireEvent.keyDown(line, { key: "Tab" })
    expect(line.value).toBe("execute run ")
    fireEvent.change(line, { target: { value: "execute run st" } })
    fireEvent.keyDown(line, { key: "Tab" })
    expect(line.value).toBe("execute run stop")
  })

  it("moves through the words with the arrows while they show, and leaves the history to them once Escape closed them", () => {
    const line = mountLine(TREE)
    fireEvent.change(line, { target: { value: "s" } })
    expect(shown()).toEqual(["say", "stop"])
    fireEvent.keyDown(line, { key: "ArrowDown" })
    expect(screen.getByRole("option", { name: "stop" }).getAttribute("aria-selected")).toBe("true")
    fireEvent.keyDown(line, { key: "ArrowDown" })
    expect(screen.getByRole("option", { name: "say" }).getAttribute("aria-selected")).toBe("true")
    fireEvent.keyDown(line, { key: "ArrowUp" })
    fireEvent.keyDown(line, { key: "Tab" })
    expect(line.value).toBe("stop")

    fireEvent.change(line, { target: { value: "s" } })
    fireEvent.keyDown(line, { key: "Escape" })
    expect(screen.queryByRole("listbox")).toBeNull()
    expect(line.value).toBe("s")
  })

  it("offers nothing for a server that published no tree", () => {
    const line = mountLine([])
    fireEvent.change(line, { target: { value: "st" } })
    expect(screen.queryByRole("listbox")).toBeNull()
  })

  it("offers only the steps steward can fill, and always the lowest", () => {
    expect(offeredSteps(undefined)).toEqual([1000])
    expect(offeredSteps(640)).toEqual([1000])
    expect(offeredSteps(7000)).toEqual([1000, 5000])
    expect(offeredSteps(10000)).toEqual([1000, 5000, 10000])
  })
})
