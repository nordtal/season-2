import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, assert, beforeAll, describe, expect, it, vi } from "vitest"

import { NotificationsDialog, useNotificationActions } from "@/app/notifications"
import { asButton, asElement, asInput } from "@/lib/test-elements"
import { stringChangesOf } from "@/lib/query-fixtures"

/**
 * The notifications dialog: one switch writes its own type and channel, and the test send carries device and type.
 *
 * `@/lib/push` is mocked, since `push.test.ts` already holds it against a fake service worker.
 */
vi.mock("@/lib/push", () => ({
  pushSupported: () => supported,
  currentPushEndpoint: async () => thisBrowser,
  subscribeToPush: async (key: string) => ({ endpoint: `https://push.example/${key}`, keys: {} }),
  unsubscribeFromPush: async () => thisBrowser,
}))

let supported = true
let thisBrowser: string | null = null

const PHONE = "https://push.example/phone"
const LAPTOP = "https://push.example/laptop"

/** jsdom lacks the pointer capture methods Radix calls on pointer down. */
beforeAll(() => {
  if (!Element.prototype.hasPointerCapture) Element.prototype.hasPointerCapture = () => false
  if (!Element.prototype.setPointerCapture) Element.prototype.setPointerCapture = () => {}
  if (!Element.prototype.releasePointerCapture) Element.prototype.releasePointerCapture = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  supported = true
  thisBrowser = null
})

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

type Call = { url: string; method: string; body: unknown }

const ALERTS = "steward/alerts"

/** The file the three thresholds live in, as `/api/setting-groups/<file>` answers it. */
function alertsFile(values: Record<string, string> = {}) {
  const value = (path: string, fallback: string) => values[path] ?? fallback
  return {
    service: "steward",
    name: "alerts",
    path: ALERTS,
    readable: true,
    writable: true,
    revision: "rev-1",
    entries: [
      entry("disk-percent", "disk-percent", value("disk-percent", "85")),
      entry("memory-percent", "memory-percent", value("memory-percent", "90")),
      entry("backup-age-hours", "backup-age-hours", value("backup-age-hours", "30")),
    ],
  }
}

function entry(path: string, key: string, value: string) {
  return {
    path,
    key,
    label: key,
    explanation: "",
    noExplanationNeeded: true,
    filled: true,
    value,
    kind: "SCALAR",
    type: "INTEGER",
    editable: true,
    secret: false,
  }
}

function backend(
  over: {
    devices?: unknown[]
    preferences?: Record<string, Record<string, boolean>>
    /** No `alerts` group in the listing at all, the read only shape. */
    noAlertsFile?: boolean
    writable?: boolean
  } = {},
) {
  const calls: Call[] = []
  const fetcher = vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(
    async (url, init) => {
      const method = init?.method ?? "GET"
      const body = init?.body ? JSON.parse(init.body) : undefined
      calls.push({ url, method, body })

      if (url === "/api/web-push/public-key") return json(200, { publicKey: "AQIDBA" })
      if (url === "/api/web-push/devices") {
        return json(
          200,
          over.devices ?? [
            { endpoint: PHONE, device: "iPhone, Safari", subscribedAt: "2026-09-18T10:00:00Z" },
            {
              endpoint: LAPTOP,
              device: "Linux, Chrome",
              subscribedAt: "2026-09-17T10:00:00Z",
              lastSentAt: "2026-09-19T08:00:00Z",
            },
          ],
        )
      }
      if (url === "/api/alerts/preferences") {
        if (method === "PUT") return json(200, body)
        return json(
          200,
          over.preferences ?? {
            service: { push: true, discord: false },
            backup: { push: true, discord: false },
            disk: { push: true, discord: false },
            memory: { push: true, discord: false },
            drift: { push: false, discord: false },
            run: { push: true, discord: true },
            payment: { push: true, discord: true },
            bot: { push: false, discord: true },
          },
        )
      }
      if (url === "/api/web-push/test") return new Response(null, { status: 204 })
      if (url === "/api/setting-groups") {
        return json(200, over.noAlertsFile ? [] : [{ ...alertsFile(), writable: over.writable ?? true }])
      }
      if (url === `/api/setting-groups/${ALERTS}`) {
        if (method === "PUT") {
          const changes = stringChangesOf(body)
          return json(200, { ...alertsFile(changes), revision: "rev-2" })
        }
        return json(200, { ...alertsFile(), writable: over.writable ?? true })
      }
      if (url === "/api/web-push/subscribe") return new Response(null, { status: 204 })
      throw new Error(`the dialog asked for ${url}, which this test did not expect`)
    },
  )
  return { calls, fetcher }
}

/** The dialog, opened, since opening is what the two queries wait for. */
function Harness() {
  const state = useNotificationActions()
  return (
    <>
      <button type="button" onClick={() => state.setOpen(true)}>
        open
      </button>
      <NotificationsDialog state={state} />
    </>
  )
}

/** Opens it inside a memory router, since the read only thresholds carry a `<Link>` that throws without one. */
async function open() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const root = createRootRoute({ component: Harness })
  const router = createRouter({
    routeTree: root,
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  fireEvent.click(await screen.findByRole("button", { name: "open" }))
  await screen.findByText("Notifications")
}

describe("the types, one switch per channel each", () => {
  it("draws every type the server answers with, in its own words, on both channels", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    /** One wait for the list, then a plain `get` per type, so a missing type is named rather than timed out. */
    await screen.findByRole("switch", { name: "Services by Push" })
    for (const label of [
      "Services",
      "Backups",
      "Disk",
      "Memory",
      "Images",
      "Failed runs",
      "Payments",
      "Discord actions",
    ]) {
      for (const channel of ["Push", "Discord"]) {
        assert.isOk(screen.getByRole("switch", { name: `${label} by ${channel}` }), `${label} by ${channel}`)
      }
    }
  })

  it("shows a channel that is off as off, rather than as the default", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    /** Waits on a switch that is on, so the wait means the answer arrived. */
    const disk = await screen.findByRole("switch", { name: "Disk by Push" })
    await waitFor(() => expect(disk.getAttribute("aria-checked")).toBe("true"))
    expect(screen.getByRole("switch", { name: "Images by Push" }).getAttribute("aria-checked")).toBe("false")
    expect(screen.getByRole("switch", { name: "Disk by Discord" }).getAttribute("aria-checked")).toBe("false")
    expect(screen.getByRole("switch", { name: "Failed runs by Discord" }).getAttribute("aria-checked")).toBe("true")
  })

  it("writes the type and channel that were flicked, and only those", async () => {
    const { calls, fetcher } = backend()
    vi.stubGlobal("fetch", fetcher)
    await open()

    const disk = await screen.findByRole("switch", { name: "Disk by Push" })
    await waitFor(() => expect(disk.getAttribute("aria-checked")).toBe("true"))
    fireEvent.click(disk)

    /** Waits for a write, then asserts its body, so a wrong body is reported as such. */
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true))
    expect(calls.filter((call) => call.method === "PUT")).toEqual([
      {
        url: "/api/alerts/preferences",
        method: "PUT",
        body: { type: "disk", channel: "push", enabled: false },
      },
    ])
  })

  it("leaves the switch where it was put, without a refetch to spring it back", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    const memory = await screen.findByRole("switch", { name: "Memory by Discord" })
    await waitFor(() => expect(memory.getAttribute("aria-checked")).toBe("false"))
    fireEvent.click(memory)

    await waitFor(() => expect(memory.getAttribute("aria-checked")).toBe("true"))
    expect(screen.getByRole("switch", { name: "Memory by Push" }).getAttribute("aria-checked")).toBe("true")
  })

  it("leaves the push switch where it was put too", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    const memory = await screen.findByRole("switch", { name: "Memory by Push" })
    await waitFor(() => expect(memory.getAttribute("aria-checked")).toBe("true"))
    fireEvent.click(memory)

    await waitFor(() => expect(memory.getAttribute("aria-checked")).toBe("false"))
  })
})

describe("the devices of this account", () => {
  it("names the browser the dialog is being read in, so two alike can be told apart", async () => {
    thisBrowser = LAPTOP
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    const row = asElement((await screen.findByText("Linux, Chrome")).closest("li"))
    expect(row.textContent).toContain("this device")
    const other = asElement(screen.getByText("iPhone, Safari").closest("li")).textContent
    expect(other).not.toContain("this device")
  })

  it("sends the test to the device that was tapped, with the type that was chosen", async () => {
    const { calls, fetcher } = backend()
    vi.stubGlobal("fetch", fetcher)
    await open()

    fireEvent.click(await screen.findByRole("button", { name: "Send a test notification to iPhone, Safari" }))
    fireEvent.click(await screen.findByRole("button", { name: "Backup missing" }))

    await waitFor(() => expect(calls.some((call) => call.url === "/api/web-push/test")).toBe(true))
    expect(calls.filter((call) => call.url === "/api/web-push/test")).toEqual([
      {
        url: "/api/web-push/test",
        method: "POST",
        body: { endpoint: PHONE, type: "backup" },
      },
    ])
  })

  it("removes one device by its own endpoint and no other", async () => {
    const { calls, fetcher } = backend()
    vi.stubGlobal("fetch", fetcher)
    await open()

    fireEvent.click(await screen.findByRole("button", { name: "Remove Linux, Chrome" }))

    await waitFor(() => expect(calls.some((call) => call.method === "DELETE")).toBe(true))
    expect(calls.filter((call) => call.method === "DELETE")).toEqual([
      {
        url: "/api/web-push/subscribe",
        method: "DELETE",
        body: { endpoint: LAPTOP },
      },
    ])
  })

  it("says so when the account has subscribed nothing at all", async () => {
    vi.stubGlobal("fetch", backend({ devices: [] }).fetcher)
    await open()

    expect(await screen.findByText("No device is subscribed.")).toBeTruthy()
  })
})

describe("nothing in it scrolls sideways", () => {
  /** jsdom has no layout, so only the rule that the scroller cannot scroll in x is held here. */
  it("keeps the scroller from being scrollable sideways at all", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    const scroller = (await screen.findByText("This device")).closest(".overflow-y-auto")
    expect(scroller).not.toBeNull()
    expect(asElement(scroller).className).toContain("overflow-x-hidden")
  })
})

/** The test send is a popover on the send button, its rows saying what will arrive. */
describe("the test send hangs off the paper plane", () => {
  it("says what each row will actually put on a lock screen, not the name of the switch", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    fireEvent.click(await screen.findByRole("button", { name: "Send a test notification to iPhone, Safari" }))

    expect(await screen.findByText("Test notifications")).toBeTruthy()
    /** Every type, in the words `AlertRouter#sample` sends. */
    for (const label of [
      "Service down",
      "Backup missing",
      "Disk filling up",
      "Memory filling up",
      "Image out of date",
      "Run failed",
      "Payment needs a look",
      "Role not given",
    ]) {
      expect(screen.getByRole("button", { name: label })).toBeTruthy()
    }
  })

  it("has no select left beside the device list", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()
    await screen.findByText("iPhone, Safari")

    expect(screen.queryByRole("combobox", { name: "Which notification to test" })).toBeNull()
  })

  it("offers a test for every type that can be switched on, and no more", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    await screen.findAllByRole("switch")
    const switches = screen.getAllByRole("switch", { name: / by Push$/ })
    fireEvent.click(screen.getByRole("button", { name: "Send a test notification to iPhone, Safari" }))
    const popover = asElement((await screen.findByText("Test notifications")).parentElement)

    /** One row per push switch, so no test sends nothing and no switch goes untested. */
    expect(within(popover).getAllByRole("button")).toHaveLength(switches.length)
  })
})

/** Keys of `steward/alerts`, written by the configuration form's PUT with its revision. */
describe("the thresholds the notifications fire on", () => {
  it("draws the numbers the file says, not the ones the light happens to hold", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    expect(asInput(await screen.findByLabelText("Disk in use")).value).toBe("85")
    expect(asInput(screen.getByLabelText("Memory in use")).value).toBe("90")
    expect(asInput(screen.getByLabelText("Newest backup")).value).toBe("30")
  })

  it("writes only the number that was typed in, with the revision it was drawn from", async () => {
    const { calls, fetcher } = backend()
    vi.stubGlobal("fetch", fetcher)
    await open()

    fireEvent.change(await screen.findByLabelText("Disk in use"), { target: { value: "70" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() =>
      expect(calls.some((call) => call.method === "PUT" && call.url.startsWith("/api/setting-groups/"))).toBe(true),
    )
    expect(calls.filter((call) => call.method === "PUT" && call.url.startsWith("/api/setting-groups/"))).toEqual([
      {
        url: `/api/setting-groups/${ALERTS}`,
        method: "PUT",
        body: { revision: "rev-1", changes: { "disk-percent": "70" } },
      },
    ])
  })

  it("has nothing to save until something was changed", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()
    await screen.findByLabelText("Disk in use")

    expect(asButton(screen.getByRole("button", { name: "Save" })).disabled).toBe(true)
  })

  it("shows the numbers and points at the page when the file cannot be written here", async () => {
    vi.stubGlobal("fetch", backend({ noAlertsFile: true }).fetcher)
    await open()
    await screen.findByText("iPhone, Safari")

    // No field that cannot write: a box somebody types into and loses is worse than a sentence.
    expect(screen.queryByLabelText("Disk in use")).toBeNull()
    expect(screen.getByRole("link", { name: "steward page" })).toBeTruthy()
  })
})

describe("this browser's own switch, which used to be the settings page's", () => {
  it("offers to turn it on when this browser has no subscription", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    expect(await screen.findByRole("button", { name: "Turn on" })).toBeTruthy()
  })

  it("offers to turn it off when it has one", async () => {
    thisBrowser = PHONE
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    expect(await screen.findByRole("button", { name: "Turn off" })).toBeTruthy()
  })

  it("says a browser that cannot push, and still lets it choose the admin channel", async () => {
    supported = false
    vi.stubGlobal("fetch", backend({ devices: [] }).fetcher)
    await open()

    expect(screen.getByText("This browser cannot receive push notifications.")).toBeTruthy()
    const discord = await screen.findByRole("switch", { name: "Services by Discord" })
    await waitFor(() => expect(asButton(discord).disabled).toBe(false))
    expect(asButton(screen.getByRole("switch", { name: "Services by Push" })).disabled).toBe(true)
    expect(screen.queryByText("No device is subscribed.")).toBeNull()
  })
})
