import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider, createMemoryHistory, createRootRoute, createRouter } from "@tanstack/react-router"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest"

import { NotificationsDialog, useNotificationActions } from "@/app/notifications"

/**
 * The dialog behind the round picture (steward/98, Till's review of 2026-09-18).
 *
 * What is worth holding here is not that four sections render - it is the four things that are
 * easy to get subtly wrong and impossible to see on screen:
 *
 * - **one switch writes one type**, and the type it writes is its own;
 * - **the test send carries the device that was tapped and the type that was chosen**, not the
 *   first device or the default type;
 * - **the browser holding the dialog is named as such** in a list where two entries can otherwise
 *   read identically ("Linux, Chrome" twice is the normal case, not the odd one);
 *
 * `@/lib/push` is mocked rather than a fake `navigator.serviceWorker` built: `push.test.ts` already
 * holds that boundary against a fake service worker, and a second copy of it here would be testing
 * the same four functions twice while this file is about what the dialog does with their answers.
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

// jsdom has neither method, and radix calls all three on pointer down - the same gap
// `snowflake-picker.test.tsx` patches. It was the Select beside "Devices" that needed it until
// steward/129; the Popover that replaced it sits on the same primitives.
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

const ALERTS = "steward-ui/steward-ui.yml"

/** The file the three thresholds live in, as `/api/config/<file>` answers it. */
function alertsFile(values: Record<string, string> = {}) {
  const value = (path: string, fallback: string) => values[path] ?? fallback
  return {
    service: "steward-ui",
    name: "steward-ui.yml",
    path: ALERTS,
    readable: true,
    writable: true,
    revision: "rev-1",
    header: [],
    entries: [
      entry("alerts.disk-percent", "disk-percent", value("alerts.disk-percent", "85")),
      entry("alerts.memory-percent", "memory-percent", value("alerts.memory-percent", "90")),
      entry("alerts.backup-age-hours", "backup-age-hours", value("alerts.backup-age-hours", "30")),
    ],
  }
}

function entry(path: string, key: string, value: string) {
  return {
    path,
    key,
    label: key,
    comments: [],
    explanation: "",
    noExplanationNeeded: true,
    filled: true,
    value,
    kind: "SCALAR",
    type: "INTEGER",
    line: 1,
    editable: true,
    secret: false,
    inSchema: true,
  }
}

function backend(
  over: {
    devices?: unknown[]
    preferences?: Record<string, boolean>
    /** No `steward-ui.yml` in the listing at all - the read-only shape. */
    noAlertsFile?: boolean
    writable?: boolean
  } = {},
) {
  const calls: Call[] = []
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET"
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
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
    if (url === "/api/web-push/preferences") {
      if (method === "PUT") return json(200, body)
      return json(
        200,
        over.preferences ?? {
          service: true,
          backup: true,
          disk: true,
          memory: true,
          drift: false,
        },
      )
    }
    if (url === "/api/web-push/test") return new Response(null, { status: 204 })
    if (url === "/api/config") {
      return json(200, over.noAlertsFile ? [] : [{ ...alertsFile(), writable: over.writable ?? true }])
    }
    if (url === `/api/config/${ALERTS}`) {
      if (method === "PUT") {
        const changes = (body as { changes: Record<string, string> }).changes
        return json(200, { ...alertsFile(changes), revision: "rev-2" })
      }
      return json(200, { ...alertsFile(), writable: over.writable ?? true })
    }
    if (url === "/api/web-push/subscribe") return new Response(null, { status: 204 })
    throw new Error(`the dialog asked for ${url}, which this test did not expect`)
  })
  return { calls, fetcher }
}

/**
 * The dialog, opened - which is how it is reached in the interface too.
 *
 * Opening it is what the two queries wait for (`useWebPushDevices(open)`), so a harness that drew
 * it open from the start would prove nothing about the thing the popover actually does.
 */
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

/**
 * A memory router around it, since steward/129: the read-only shape of the thresholds section
 * carries a `<Link>` to the service page, and a `Link` outside a `RouterProvider` throws rather
 * than degrading - `Cannot read properties of null (reading 'isServer')`, from every test at once.
 */
async function open() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const root = createRootRoute({ component: Harness })
  const router = createRouter({
    routeTree: root,
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
  fireEvent.click(await screen.findByRole("button", { name: "open" }))
  await screen.findByText("Notifications")
}

describe("the types, one switch each", () => {
  it("draws every type the server answers with, in its own words", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    // One wait for the list to be there at all, then a plain `get` per type: a `findBy` per label
    // would turn a missing type into a five-second timeout instead of a sentence naming it.
    await screen.findByRole("switch", { name: "Services" })
    for (const label of ["Services", "Backups", "Disk", "Memory", "Images"]) {
      expect(screen.getByRole("switch", { name: label }), label).toBeTruthy()
    }
  })

  it("shows a type that is off as off, rather than as the default", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    // Waited on a type that is ON, so that the wait means "the answer arrived" and the assertion
    // below means "it was read" - waiting on the off one would pass by timing out into a switch
    // that is off because nothing has loaded yet.
    const disk = await screen.findByRole("switch", { name: "Disk" })
    await waitFor(() => expect(disk.getAttribute("aria-checked")).toBe("true"))
    expect(screen.getByRole("switch", { name: "Images" }).getAttribute("aria-checked")).toBe("false")
  })

  it("writes the type that was flicked, and only that one", async () => {
    const { calls, fetcher } = backend()
    vi.stubGlobal("fetch", fetcher)
    await open()

    const disk = await screen.findByRole("switch", { name: "Disk" })
    await waitFor(() => expect(disk.getAttribute("aria-checked")).toBe("true"))
    fireEvent.click(disk)

    // Waited for on "something was written", asserted on "what was written" - a `waitFor` around
    // the equality itself would report a timeout rather than the wrong body, which is the one
    // thing this test is for.
    await waitFor(() => expect(calls.some((call) => call.method === "PUT")).toBe(true))
    expect(calls.filter((call) => call.method === "PUT")).toEqual([
      {
        url: "/api/web-push/preferences",
        method: "PUT",
        body: { type: "disk", enabled: false },
      },
    ])
  })

  it("leaves the switch where it was put, without a refetch to spring it back", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    const memory = await screen.findByRole("switch", { name: "Memory" })
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

    const row = (await screen.findByText("Linux, Chrome")).closest("li") as HTMLElement
    expect(row.textContent).toContain("this device")
    const other = (screen.getByText("iPhone, Safari").closest("li") as HTMLElement).textContent
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

/**
 * steward/129. The select that used to stand beside the word "Devices" is a popover on the button
 * that does the sending, and its rows say what will arrive rather than what the switch above is
 * called.
 */
describe("nothing in it scrolls sideways (steward/129)", () => {
  /**
   * Measured rather than asserted, everywhere except here: `/home/dev/ui-shots/tool/notify.mjs`
   * opens this dialog at 390px and reports every box past the edge. jsdom has no layout and can
   * therefore only hold the one rule that made it fit - the scroller cannot scroll in x, so a
   * control that refuses to shrink is a clipped control rather than a sheet that slides.
   */
  it("keeps the scroller from being scrollable sideways at all", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    const scroller = (await screen.findByText("This device")).closest(".overflow-y-auto")
    expect(scroller).not.toBeNull()
    expect((scroller as HTMLElement).className).toContain("overflow-x-hidden")
  })
})

describe("the test send hangs off the paper plane", () => {
  it("says what each row will actually put on a lock screen, not the name of the switch", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    fireEvent.click(await screen.findByRole("button", { name: "Send a test notification to iPhone, Safari" }))

    expect(await screen.findByText("Test notifications")).toBeTruthy()
    // Every one of the five, in the words `AlertWatch#sample` really sends. "Services" is the
    // switch; "Service down" is the notification.
    for (const label of [
      "Service down",
      "Backup missing",
      "Disk filling up",
      "Memory filling up",
      "Image out of date",
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

    const switches = await screen.findAllByRole("switch")
    fireEvent.click(screen.getByRole("button", { name: "Send a test notification to iPhone, Safari" }))
    const popover = (await screen.findByText("Test notifications")).parentElement as HTMLElement

    // One row per switch: a type that cannot be tested honestly would be a button that sends
    // nothing, and a type with no switch would be a test for something nobody can receive.
    expect(within(popover).getAllByRole("button")).toHaveLength(switches.length)
  })
})

/**
 * steward/129. The three numbers are keys of `steward-ui/steward-ui.yml`, and the same PUT the
 * configuration form uses writes them - revision and all, so two open forms still collide loudly.
 */
describe("the thresholds the notifications fire on", () => {
  it("draws the numbers the file says, not the ones the light happens to hold", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()

    expect(((await screen.findByLabelText("Disk in use")) as HTMLInputElement).value).toBe("85")
    expect((screen.getByLabelText("Memory in use") as HTMLInputElement).value).toBe("90")
    expect((screen.getByLabelText("Newest backup") as HTMLInputElement).value).toBe("30")
  })

  it("writes only the number that was typed in, with the revision it was drawn from", async () => {
    const { calls, fetcher } = backend()
    vi.stubGlobal("fetch", fetcher)
    await open()

    fireEvent.change(await screen.findByLabelText("Disk in use"), { target: { value: "70" } })
    fireEvent.click(screen.getByRole("button", { name: "Save" }))

    await waitFor(() =>
      expect(calls.some((call) => call.method === "PUT" && call.url.startsWith("/api/config/"))).toBe(true),
    )
    expect(calls.filter((call) => call.method === "PUT" && call.url.startsWith("/api/config/"))).toEqual([
      {
        url: `/api/config/${ALERTS}`,
        method: "PUT",
        body: { revision: "rev-1", changes: { "alerts.disk-percent": "70" } },
      },
    ])
  })

  it("has nothing to save until something was changed", async () => {
    vi.stubGlobal("fetch", backend().fetcher)
    await open()
    await screen.findByLabelText("Disk in use")

    expect((screen.getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(true)
  })

  it("shows the numbers and points at the page when the file cannot be written here", async () => {
    vi.stubGlobal("fetch", backend({ noAlertsFile: true }).fetcher)
    await open()
    await screen.findByText("iPhone, Safari")

    // No field that cannot write: a box somebody types into and loses is worse than a sentence.
    expect(screen.queryByLabelText("Disk in use")).toBeNull()
    expect(screen.getByRole("link", { name: "steward-ui page" })).toBeTruthy()
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

  it("says a browser that cannot do this at all, rather than drawing dead switches", async () => {
    supported = false
    vi.stubGlobal("fetch", backend({ devices: [] }).fetcher)
    await open()

    expect(screen.getByText("This browser cannot receive push notifications.")).toBeTruthy()
    expect(screen.queryByRole("switch")).toBeNull()
  })
})
