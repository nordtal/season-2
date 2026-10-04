import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { Shell } from "@/app/shell"

/**
 * Which of the doors `/api/me` opens: sign in on a 401, `DoorIsStuck` on a fault, setup without a key.
 *
 * The signed in branch needs a router that jsdom cannot carry, so it is not rendered here.
 */

function answer(status: number, body: unknown): Response {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

let fetched: ReturnType<typeof vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>>

/** Retries off: `useMe` sets `retry: false` itself, and this keeps the client honest about it. */
function draw() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <Shell />
    </QueryClientProvider>,
  )
}

/** The sign-in page, identified by the one thing only it has. */
const signInButton = () => screen.queryByRole("link", { name: /Sign in with Discord/ })

/** The stuck door, identified by the sentence that says it is not a session problem. */
const stuckDoor = () => screen.queryByText(/not an expired session/)

beforeEach(() => {
  fetched = vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>()
  vi.stubGlobal("fetch", fetched)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("Shell - the answer that means the session is gone", () => {
  it("draws the sign-in page when /api/me says nobody is signed in", async () => {
    fetched.mockResolvedValue(answer(200, { signedIn: false }))
    draw()

    await waitFor(() => expect(signInButton()).not.toBeNull())
    expect(stuckDoor()).toBeNull()
  })

  it("draws it for a 401 as well, which is the only status that means this", async () => {
    fetched.mockResolvedValue(answer(401, { error: "sign in first" }))
    draw()

    await waitFor(() => expect(signInButton()).not.toBeNull())
    expect(stuckDoor()).toBeNull()
  })

  it("accuses nobody at all while the answer is still on its way", async () => {
    /** Neither door, so no sidebar of 401ing pages flashes up before the answer. */
    fetched.mockImplementation(() => new Promise<Response>(() => {}))
    draw()

    await waitFor(() => expect(fetched).toHaveBeenCalled())
    expect(signInButton()).toBeNull()
    expect(stuckDoor()).toBeNull()
  })
})

describe("Shell - every other answer is a fault, not a missing session", () => {
  /** 500, 502, 429 and 403, none of which signing in again would fix. */
  const faults: Array<[number, string]> = [
    [500, "Internal error."],
    [502, "steward is not answering."],
    [429, "Too many requests."],
    [403, "Nothing happens here without the admin role."],
  ]

  for (const [status, message] of faults) {
    it(`shows the stuck door and not the sign-in page for ${status}`, async () => {
      fetched.mockResolvedValue(answer(status, { error: message }))
      draw()

      await waitFor(() => expect(stuckDoor()).not.toBeNull())
      expect(signInButton()).toBeNull()
      expect(screen.getByRole("alert").textContent).toContain(message)
      expect(screen.getByRole("alert").textContent).toContain(`HTTP ${status}`)
    })
  }

  it("shows it when the request never arrived anywhere", async () => {
    /** `api()` turns a rejected fetch into an ApiError with status 0, which is not 401. */
    fetched.mockRejectedValue(new TypeError("Failed to fetch"))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(signInButton()).toBeNull()
    expect(screen.getByRole("alert").textContent).toContain("The interface cannot be reached.")
  })

  it("shows it for a failure that is not an ApiError at all", async () => {
    /** A body that is not an object makes `useMe` throw a TypeError, which the `instanceof` half catches. */
    fetched.mockResolvedValue(answer(200, null))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(signInButton()).toBeNull()
  })

  it("offers to ask again, and asking again is a second request", async () => {
    // The one action that can help.
    fetched.mockResolvedValue(answer(500, { error: "Internal error." }))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(fetched).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole("button", { name: /Try again/ }))

    await waitFor(() => expect(fetched).toHaveBeenCalledTimes(2))
    expect(stuckDoor()).not.toBeNull()
  })

  it("keeps the shell out of the way even though the pages could report it themselves", async () => {
    /** The CSRF token arrives with this answer, so without it every write would be refused. */
    fetched.mockResolvedValue(answer(500, { error: "Internal error." }))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(screen.queryByRole("navigation")).toBeNull()
    expect(screen.queryByRole("main")).toBeNull()
  })
})

/** The setup page, identified by the one sentence only it has. */
function setupPage() {
  return screen.queryByText(/One more thing: your security key/)
}

/** A signed-in session-info answer carrying the given security keys. */
function signedIn(keys: unknown) {
  return {
    signedIn: true,
    id: "1",
    name: "ally",
    csrf: "t",
    relyingPartyId: "nordtal.eu",
    keys,
  }
}

describe("Shell - signed in, and still not in", () => {
  it("draws the security key page for an account that has none", async () => {
    fetched.mockResolvedValue(answer(200, signedIn([])))
    draw()

    await waitFor(() => expect(setupPage()).not.toBeNull())
    /** Without a key every other route is a 403, so no shell is drawn. */
    expect(screen.queryByRole("navigation")).toBeNull()
    expect(signInButton()).toBeNull()
    expect(stuckDoor()).toBeNull()
  })

  it("does not draw it for somebody who is not signed in at all", async () => {
    /** `keys` is absent when signed out, so the key gate must come after the signed out branch. */
    fetched.mockResolvedValue(answer(200, { signedIn: false }))
    draw()

    await waitFor(() => expect(signInButton()).not.toBeNull())
    expect(setupPage()).toBeNull()
  })

  it("does not draw it for a 401 either", async () => {
    fetched.mockResolvedValue(answer(401, { error: "sign in first" }))
    draw()

    await waitFor(() => expect(signInButton()).not.toBeNull())
    expect(setupPage()).toBeNull()
  })

  it("does not draw it when the request failed and nobody knows about any keys", async () => {
    /** `me.data` is undefined here, and a key gate defaulting to zero would ask for a key on every 500. */
    fetched.mockResolvedValue(answer(500, { error: "Internal error." }))
    draw()

    await waitFor(() => expect(stuckDoor()).not.toBeNull())
    expect(setupPage()).toBeNull()
  })
})
