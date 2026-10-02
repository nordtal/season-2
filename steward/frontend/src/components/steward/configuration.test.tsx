import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { resetDrafts } from "@/lib/drafts"
import { setPendingJump, takePendingJump } from "@/lib/settings-search"
import type { ConfigEntry } from "@/lib/api"
import {
  Settings,
  asInput,
  backend,
  draw,
  entry,
  json,
  location,
  open,
  search,
} from "@/components/steward/configuration.fixtures"

/**
 * The configuration form drawn from the schema beside a file, against a fake backend answering by URL.
 *
 * No `jest-dom`, so assertions read DOM properties directly.
 */
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetDrafts()
})

describe("the file row", () => {
  it("shows the plain-text file name and no second line with the path", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "steward/steward": {
          ...location({ path: "steward/steward", name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries: [],
        },
      }),
    )

    draw(<Settings service="steward" />)

    await screen.findByText("Steward")
    /** The path is not shown under the name. */
    expect(screen.queryByText("steward.yml")).toBeNull()
  })

  it("lists the translations, then the groups of settings by name", async () => {
    const files = ["sounds", "config", "milestones"].map((name) => ({
      service: "smp",
      name,
      path: `smp/${name}`,
      label: "",
      live: true,
      readable: true,
      writable: true,
    }))
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url === "/api/setting-groups") return json(files)
        if (url === "/api/messages")
          return json([{ service: "smp", module: "smp", path: "smp/smp/messages", writable: true }])
        throw new Error(`the list asked for ${url}, which this test did not expect`)
      }),
    )

    draw(<Settings service="smp" />)

    await screen.findByText("SMP Translations")
    const nav = screen.getByRole("navigation", { name: "Files" })
    const lines = Array.from(nav.querySelectorAll("h3, button")).map((node) => node.textContent?.trim())
    expect(lines).toEqual(["SMP Translations", "Config", "Milestones", "Sounds"])
  })

  it("draws no group headings", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        "discord-bot/bot.yml": {
          ...location({ path: "discord-bot/bot.yml", name: "bot.yml", service: "discord-bot" }),
          revision: "r1",
          restartRequired: false,
          entries: [],
        },
      }),
    )
    draw(<Settings service="discord-bot" />)
    await screen.findByText("Bot")
    expect(screen.getByRole("navigation", { name: "Files" }).querySelector("h3")).toBeNull()
  })
})

/** The search box of an open file: label, path, value and explanation, never a secret's value, and a pruned tree. */
describe("searching a file", () => {
  const file = "steward/steward"

  function withEntries(entries: ConfigEntry[]) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries,
        },
      }),
    )
  }

  const twoFields = [
    entry({ path: "agent.base-url", key: "base-url", label: "Base url", value: "http://steward:8081" }),
    entry({
      path: "limits.max-attempts",
      key: "max-attempts",
      label: "Max attempts",
      explanation: "How many times a failed job is retried before it is given up on.",
    }),
  ]

  it("finds a setting by its label and leaves the others out", async () => {
    withEntries(twoFields)
    draw(<Settings service="steward" />)
    await open("Steward")

    await search("base url")

    await screen.findByText("Base url")
    expect(screen.queryByText("Max attempts")).toBeNull()
  })

  it("finds a setting by its current value", async () => {
    withEntries(twoFields)
    draw(<Settings service="steward" />)
    await open("Steward")

    await search("8081")

    await screen.findByText("Base url")
    expect(screen.queryByText("Max attempts")).toBeNull()
  })

  it("finds a setting by its explanation text", async () => {
    withEntries(twoFields)
    draw(<Settings service="steward" />)
    await open("Steward")

    await search("given up")

    await screen.findByText("Max attempts")
    expect(screen.queryByText("Base url")).toBeNull()
  })

  it("never finds a secret by its value", async () => {
    const token = "MTA1NzE4.super-secret-discord-token"
    withEntries([
      /** A secret carrying a value despite the wire contract, which the search must still refuse. */
      entry({
        path: "discord.bot-token",
        key: "bot-token",
        label: "Bot token",
        secret: true,
        environmentOverridden: false,
        value: token,
      }),
    ])
    draw(<Settings service="steward" />)
    await open("Steward")

    await search(token)

    await screen.findByText("No match.")
    expect(screen.queryByText("Bot token")).toBeNull()
  })

  it("still finds that secret entry by its label - only the value is excluded", async () => {
    withEntries([
      entry({
        path: "discord.bot-token",
        key: "bot-token",
        label: "Bot token",
        secret: true,
        environmentOverridden: false,
        value: "irrelevant",
      }),
    ])
    draw(<Settings service="steward" />)
    await open("Steward")

    await search("bot token")

    expect(await screen.findByText("Bot token")).not.toBeNull()
  })
})

describe("headings and explanations", () => {
  const file = "steward/steward"

  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries: [
            entry({ path: "agent", key: "agent", label: "Agent", kind: "MAP" }),
            entry({ path: "agent.limits", key: "limits", label: "Limits", kind: "MAP" }),
            /** Three levels deep: a heading stops here and the leaf's path carries the rest. */
            entry({ path: "agent.limits.retry", key: "retry", label: "Retry", kind: "MAP" }),
            entry({
              path: "agent.limits.retry.max-attempts",
              key: "max-attempts",
              label: "Max attempts",
              value: "3",
              type: "INTEGER",
              explanation: "How many times a failed job is retried before it is given up on.",
            }),
            entry({
              path: "silent",
              key: "silent",
              label: "Silent",
              explanation: "Words the schema says nobody needs.",
              noExplanationNeeded: true,
            }),
          ],
        },
      }),
    )
  })

  it("folds a chain of single-child sections into one row that names all of them", async () => {
    draw(<Settings service="steward" />)
    await open("Steward")

    const row = await screen.findByRole("button", { name: /Agent.*Limits.*Retry/ })
    expect(row.getAttribute("aria-expanded")).toBe("true")
    screen.getByText("Max attempts")
  })

  it("shows no key paths, only names", async () => {
    draw(<Settings service="steward" />)
    await open("Steward")
    await screen.findByText("Max attempts")

    expect(screen.queryByText("agent.limits.retry.max-attempts")).toBeNull()
  })

  it("closes a section on a click, and the fields in it go", async () => {
    draw(<Settings service="steward" />)
    await open("Steward")

    fireEvent.click(await screen.findByRole("button", { name: /Agent.*Limits.*Retry/ }))

    await waitFor(() => expect(screen.queryByText("Max attempts")).toBeNull())
  })

  it("shows the schema's explanation under a label", async () => {
    draw(<Settings service="steward" />)
    await open("Steward")

    expect(await screen.findByText("How many times a failed job is retried before it is given up on.")).not.toBeNull()
  })

  it("draws literally no text when the schema says no explanation is needed", async () => {
    draw(<Settings service="steward" />)
    await open("Steward")
    await screen.findByText("Max attempts")

    expect(screen.queryByText("Words the schema says nobody needs.")).toBeNull()
  })
})

/**
 * A field an environment variable overrides must not draw as editable, since saving it changes nothing.
 */
describe("environment overrides", () => {
  const file = "steward/steward"

  function withField(over: Partial<ConfigEntry>) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries: [
            entry({
              path: "agent.base-url",
              key: "base-url",
              label: "Base url",
              value: "http://steward:8081",
              ...over,
            }),
          ],
        },
      }),
    )
  }

  it("marks a field the environment currently overrides, and leaves it editable", async () => {
    withField({ environmentOverridden: true })
    draw(<Settings service="steward" />)
    await open("Steward")

    await screen.findByText("env override")
    const input = asInput(await screen.findByDisplayValue("http://steward:8081"))
    expect(input.disabled).toBe(false)
  })

  it("shows nothing when the environment does not override this field", async () => {
    withField({ environmentOverridden: false })
    draw(<Settings service="steward" />)
    await open("Steward")
    await screen.findByText("Base url")

    expect(screen.queryByText("env override")).toBeNull()
  })
})

describe("a schema's allowed values", () => {
  const file = "steward/steward"

  it("becomes a closed select with no free text when strict", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries: [
            entry({
              path: "region",
              key: "region",
              label: "Region",
              value: "eu",
              choices: { values: ["eu", "us"], strict: true },
            }),
          ],
        },
      }),
    )
    draw(<Settings service="steward" />)
    await open("Steward")

    const combobox = await screen.findByRole("combobox")
    expect(combobox.textContent).toContain("eu")
    expect(screen.queryByLabelText("Free text")).toBeNull()
  })

  it("is a suggestion beside a free-text field when not strict", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries: [
            entry({
              path: "color",
              key: "color",
              label: "Color",
              value: "custom-magenta",
              choices: { values: ["red", "green", "blue"], strict: false },
            }),
          ],
        },
      }),
    )
    draw(<Settings service="steward" />)
    await open("Steward")

    await screen.findByRole("combobox")
    const free = asInput(screen.getByLabelText("Free text"))
    expect(free.value).toBe("custom-magenta")
  })
})

describe("a group whose stored values the service refused", () => {
  it("says why, so the values in use are known to be the defaults", async () => {
    const file = "smp/config"
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "config", service: "smp" }),
          problem: "view-distance must be at least 2",
          revision: "r1",
          restartRequired: false,
          entries: [entry({ path: "view-distance", key: "view-distance", label: "View distance", value: "8" })],
        },
      }),
    )
    draw(<Settings service="smp" />)
    await open("Config")

    expect(await screen.findByText("Refused: view-distance must be at least 2")).toBeTruthy()
  })
})

/**
 * A search hit must also work when this page is already open, where `navigate` remounts nothing.
 *
 * Otherwise the jump stays in the map and fires on the next visit.
 */
describe("a hit that arrives while this page is already open", () => {
  const file = "steward/steward"

  function drawWith(entries: ConfigEntry[]) {
    vi.stubGlobal(
      "fetch",
      backend({
        [file]: {
          ...location({ path: file, name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries,
        },
      }),
    )
    return draw(<Settings service="steward" />)
  }

  afterEach(() => {
    /** The map is module level, so no jump may survive into the next test. */
    takePendingJump("steward")
  })

  it("opens the file and lands on the field, without the page having remounted", async () => {
    drawWith([
      entry({ path: "agent.base-url", key: "base-url", label: "Base url" }),
      entry({ path: "agent.token", key: "token", label: "Token" }),
    ])
    await screen.findByText("Steward")
    // Closed to begin with: the field is in a file nobody has opened.
    expect(screen.queryByText("Base url")).toBeNull()

    setPendingJump("steward", { file, path: "agent.base-url" })

    await waitFor(() => expect(screen.queryByText("Base url")).not.toBeNull())
  })

  it("consumes the jump, so arriving here again does not reopen it", async () => {
    drawWith([entry({ path: "agent.base-url", key: "base-url", label: "Base url" })])
    await screen.findByText("Steward")

    setPendingJump("steward", { file, path: "agent.base-url" })
    await waitFor(() => expect(screen.queryByText("Base url")).not.toBeNull())

    expect(takePendingJump("steward")).toBeUndefined()
  })

  it("lands again on a second, identical hit while still standing on the field", async () => {
    drawWith([entry({ path: "agent.base-url", key: "base-url", label: "Base url" })])
    await screen.findByText("Steward")
    const scroll = vi.spyOn(Element.prototype, "scrollIntoView")
    try {
      setPendingJump("steward", { file, path: "agent.base-url" })
      await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1), { timeout: 1500 })

      setPendingJump("steward", { file, path: "agent.base-url" })
      await waitFor(() => expect(scroll).toHaveBeenCalledTimes(2), { timeout: 1500 })
    } finally {
      scroll.mockRestore()
    }
  })

  it("ignores a jump meant for another service", async () => {
    drawWith([entry({ path: "agent.base-url", key: "base-url", label: "Base url" })])
    await screen.findByText("Steward")

    setPendingJump("smp", { file: "smp/steward.yml", path: "grave.decay.enabled" })

    // Still shut, and the other service's jump is still there for the page it was meant for.
    await waitFor(() => expect(screen.queryByText("Base url")).toBeNull())
    expect(takePendingJump("smp")).toEqual({
      file: "smp/steward.yml",
      path: "grave.decay.enabled",
    })
  })
})

describe("the arrow back to the top", () => {
  it("shows only once the top of the file has scrolled out of view", async () => {
    let report: ((entries: Array<{ isIntersecting: boolean }>) => void) | null = null
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) {
          report = callback
        }
        observe() {
          report?.([{ isIntersecting: true }])
        }
        disconnect() {}
        unobserve() {}
      },
    )
    vi.stubGlobal(
      "fetch",
      backend({
        "steward/steward": {
          ...location({ path: "steward/steward", name: "steward" }),
          revision: "r1",
          restartRequired: false,
          entries: [entry({ path: "port", key: "port", value: "8080" })],
        },
      }),
    )
    draw(<Settings service="steward" />)
    await open("Steward")
    await screen.findByRole("searchbox", { name: "Search this file" })

    // Three fields and nothing scrolls: an arrow here would do nothing.
    expect(screen.queryByRole("button", { name: "Back to the top" })).toBeNull()

    act(() => report?.([{ isIntersecting: false }]))
    expect(screen.getByRole("button", { name: "Back to the top" })).toBeTruthy()
  })
})
