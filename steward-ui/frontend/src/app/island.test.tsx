import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { UserMenu, initials } from "@/app/user-menu"
import { sidebarDefaultOpen } from "@/app/sidebar-state"
import type { Me } from "@/lib/api"

/**
 * The account picture and its popover, which is what the settings page's account half became
 * (steward/89), and the cookie the navigation's state is kept in.
 *
 * Rendered here rather than through the frame, because none of it needs a router. The island
 * itself does - its mark and its trail are router links - so it is drawn in `frames.test.tsx`.
 */
const KEYS = [
  {
    id: "k1",
    label: "YubiKey on my keyring",
    registeredAt: "2026-09-01T10:00:00Z",
    lastUsedAt: "2026-09-16T10:00:00Z",
  },
  { id: "k2", label: "Phone", registeredAt: "2026-09-02T10:00:00Z" },
]

const ME: Me = {
  signedIn: true,
  id: "214906139328839681",
  name: "till",
  csrf: "t",
  webauthn: "required",
  relyingPartyId: "nordtal.eu",
  verified: true,
  keys: KEYS,
}

function withQueries(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("the account popover is the settings page's account half", () => {
  it("shows the keys as a list, without a second click and without a dialog", async () => {
    withQueries(<UserMenu me={ME} />)

    fireEvent.click(screen.getByRole("button", { name: /Account/ }))

    // Till, 2026-09-17: the keys stand in the popover as a list. Not behind a disclosure, not
    // behind a dialog - a dialog opens only where one belongs, and reading is not one of those.
    expect(await screen.findByText("YubiKey on my keyring")).toBeTruthy()
    expect(screen.getByText("Phone")).toBeTruthy()
    expect(screen.getByText("till")).toBeTruthy()
    expect(screen.getByRole("button", { name: /Sign out/ })).toBeTruthy()
    expect(screen.queryByText("Add a security key")).toBeNull()
    expect(screen.queryByRole("alertdialog")).toBeNull()
  })

  it("opens a dialog for registering a key, which is where one belongs", async () => {
    withQueries(<UserMenu me={ME} />)
    fireEvent.click(screen.getByRole("button", { name: /Account/ }))

    fireEvent.click(await screen.findByRole("button", { name: /Add/ }))

    expect(await screen.findByText("Add a security key")).toBeTruthy()
    expect(screen.getByLabelText("What do you call this one?")).toBeTruthy()
  })

  it("asks before removing one, and names the one it means", async () => {
    withQueries(<UserMenu me={ME} />)
    fireEvent.click(screen.getByRole("button", { name: /Account/ }))

    fireEvent.click(await screen.findByRole("button", { name: "Remove Phone" }))

    const question = await screen.findByRole("alertdialog")
    expect(question.textContent).toContain("Phone")
    expect(question.textContent).toContain("The other keys on this account keep working")
  })

  it("stays tappable for an account with no picture on record", () => {
    // The fallback is not a nicety: a way to sign out that is there most of the time is worse
    // than one that is plain. `discordAvatarUrl` absent is the ordinary case (steward/91), not an
    // error - an account can be signed into Steward without ever having a `person` row at all.
    withQueries(<UserMenu me={{ ...ME, name: "till hofmann" }} />)

    const trigger = screen.getByRole("button", { name: /Account/ })
    expect(trigger.textContent).toBe("th")
  })

  it("draws the Discord picture once /api/me carries one (steward/91)", () => {
    withQueries(<UserMenu me={{ ...ME, discordAvatarUrl: "https://cdn.discordapp.com/a.png" }} />)

    const trigger = screen.getByRole("button", { name: /Account/ })
    const img = trigger.querySelector("img")
    expect(img).toBeTruthy()
    expect(img?.getAttribute("src")).toBe("https://cdn.discordapp.com/a.png")
  })

  it("falls back to initials, and stays tappable, when the picture fails to load", () => {
    // The guard steward/91 asks for: a broken image is the same fallback as no field at all, not
    // a broken button.
    withQueries(<UserMenu me={{ ...ME, discordAvatarUrl: "https://cdn.discordapp.com/gone.png" }} />)

    const trigger = screen.getByRole("button", { name: /Account/ })
    const img = trigger.querySelector("img")
    expect(img).toBeTruthy()

    fireEvent.error(img as HTMLImageElement)

    expect(trigger.querySelector("img")).toBeNull()
    expect(trigger.textContent).toBe("ti")
  })

  it("falls back to initials for an empty name too, with no picture in the answer", () => {
    withQueries(<UserMenu me={{ ...ME, name: "" }} />)

    const trigger = screen.getByRole("button", { name: "Account" })
    expect(trigger.querySelector("img")).toBeNull()
    expect(trigger.textContent).toBe("?")
  })
})

describe("initials, taken as they are written", () => {
  it("uses the first two names", () => expect(initials("till hofmann")).toBe("th"))
  it("uses two letters of a single name", () => expect(initials("till")).toBe("ti"))
  it("does not force capitals, because half this interface is lowercase on purpose", () => {
    expect(initials("Till Hofmann")).toBe("TH")
    expect(initials("till")).toBe("ti")
  })
  it("draws something for an account with no name at all", () => {
    expect(initials(undefined)).toBe("?")
    expect(initials("   ")).toBe("?")
  })
})

describe("what the sidebar remembers", () => {
  it("is open unless the cookie says otherwise", () => {
    expect(sidebarDefaultOpen("")).toBe(true)
    expect(sidebarDefaultOpen("other=1")).toBe(true)
    expect(sidebarDefaultOpen("sidebar_state=true")).toBe(true)
    expect(sidebarDefaultOpen("sidebar_state=nonsense")).toBe(true)
  })

  it("is closed when it says so, wherever in the jar it sits", () => {
    expect(sidebarDefaultOpen("sidebar_state=false")).toBe(false)
    expect(sidebarDefaultOpen("a=1; sidebar_state=false; b=2")).toBe(false)
  })
})
