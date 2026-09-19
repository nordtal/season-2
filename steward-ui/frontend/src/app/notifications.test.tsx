import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
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
 * - **nothing about notifications is left on `/settings`**, which is the whole of what "moves
 *   there" means and the one part a rendering test cannot ask.
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

// jsdom has neither method, and radix's Select trigger calls both on pointer down - the same gap
// `snowflake-picker.test.tsx` patches, for the same component.
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

function backend(over: { devices?: unknown[]; preferences?: Record<string, boolean> } = {}) {
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

async function open() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <Harness />
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole("button", { name: "open" }))
  await screen.findByText("Notifications")
}

/** Opens radix's Select the way a pointer does - see the `beforeAll` polyfill. */
function openSelect(trigger: HTMLElement) {
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false })
  fireEvent.click(trigger)
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

    openSelect(await screen.findByRole("combobox", { name: "Which notification to test" }))
    fireEvent.click(await screen.findByRole("option", { name: "Backups" }))

    fireEvent.click(screen.getByRole("button", { name: "Send a test notification to iPhone, Safari" }))

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

/**
 * The half of "moves there" that a rendering test cannot ask.
 *
 * Till's review is about one switch being somewhere nobody looked. Leaving a copy of it on the page
 * it came from would be the same mistake with an extra step, and the failure is invisible: both
 * pages work, and the one that is wrong is whichever was not used last.
 */
describe("nothing of it stayed on /settings", () => {
  const settings = readFileSync(
    path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../pages/settings.tsx"),
    "utf8",
  )
  /** Comments blanked: the page says in prose where the switch went, and that is not a switch. */
  const code = settings
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/^\s*\/\/.*$/gm, " ")

  it("reads the page it is about, so an empty result means something", () => {
    expect(code).toContain("SettingsPage")
    expect(code).toContain("Thresholds")
  })

  it("has no web-push control left in it", () => {
    expect(code).not.toContain("WebPush")
    expect(code).not.toContain("web-push")
    expect(code).not.toContain("useSubscribeWebPush")
    expect(code).not.toContain("pushSupported")
  })
})
