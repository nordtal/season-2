import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { Island } from "@/app/island"
import { UserMenu, initials } from "@/app/user-menu"
import { sidebarDefaultOpen } from "@/app/sidebar-state"
import type { Me } from "@/lib/api"

/**
 * The two objects the header became (steward/89): the island at the top left, and the account
 * picture level with it whose popover is the settings page's account half.
 *
 * Both are rendered here rather than through the shell, and that is not a shortcut - the shell
 * needs a router, a route tree and a memory history, which is why `shell.test.tsx` says in its own
 * footnote that the signed-in branch is not reachable in a test. The island takes its crumbs as a
 * prop precisely so that this one is: the interesting half is what is drawn and what a tap does,
 * and neither of those needs a URL.
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

const CRUMBS = [
  { label: "Overview", href: "/" },
  { label: "Services", href: "/services" },
  { label: "steward-worker", href: "/services/steward-worker" },
]

function withQueries(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("the island carries the path and the toggle", () => {
  it("draws the trail and calls back when the toggle is tapped", () => {
    const toggle = vi.fn()
    render(<Island crumbs={CRUMBS} expanded={false} onToggle={toggle} />)

    expect(screen.getByText("steward-worker")).toBeTruthy()
    // "Overview" is drawn as the Nordtal mark and the bare word, which is the island's first
    // element by order (steward/89) - the label of `crumbs[0]` never appears.
    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByText("Overview")).toBeNull()
    expect(screen.getByText("Services")).toBeTruthy()

    fireEvent.click(screen.getByRole("button", { name: "Navigation" }))
    expect(toggle).toHaveBeenCalledTimes(1)
  })

  it("gives the ancestors up before the page name, when the trail does not fit", () => {
    // jsdom has no layout, so this cannot be measured in pixels - it is the rule that produces
    // the pixels. Measured in a browser at 1440px on /designs/network before the rule existed:
    // in shell A the trail lives in a 256px sidebar head, every ancestor refused to shrink, and
    // the current page was the one crumb squeezed to nothing - a trail ending in a separator
    // pointing at empty space. Both halves are load-bearing and neither is obvious a year from
    // now, which is why the classes are asserted rather than trusted.
    render(<Island crumbs={CRUMBS} expanded={false} onToggle={() => undefined} />)

    const items = Array.from(document.querySelectorAll("li")).filter(
      (li) => li.getAttribute("data-slot") === "breadcrumb-item",
    )
    expect(items.length, "One item per crumb, separators excluded.").toBe(CRUMBS.length)

    for (const item of items) {
      expect(
        item.className,
        "Without `min-w-0` a flex item cannot go below its content width, so it cannot truncate.",
      ).toContain("min-w-0")
    }

    const page = items[items.length - 1]!
    // The brand crumb is exempt and has its own reason, written out in `Crumbs`: it carries the
    // mark and the bare word "Steward", and a head that lets that go to an ellipsis before an
    // in-between segment does is un-branding itself under its own name.
    for (const ancestor of items.slice(1, -1)) {
      expect(
        ancestor.className,
        "An ancestor has to give way first: a flex item sheds space in proportion to its shrink" +
          " factor, so the two must not be equal or the page name shrinks alongside them.",
      ).toContain("shrink-[999]")
    }
    expect(page.className).not.toContain("shrink-[999]")
  })

  it("says whether the navigation is open, for anything that cannot see the icon", () => {
    render(<Island crumbs={CRUMBS} expanded onToggle={() => undefined} />)
    expect(screen.getByRole("button", { name: "Navigation" }).getAttribute("aria-expanded"))
      .toBe("true")
  })

  it("shows the mark and the word instead of the path once the column is open", () => {
    // Till's third correction of 2026-09-17: with the navigation standing open the head carries
    // only the logo, "Steward" and the toggle. The column itself is the better answer to "where
    // am I" at that point - the current page is the row marked in blue - and two answers to one
    // question is the weaker one winning half the time.
    render(<Island crumbs={CRUMBS} expanded onToggle={() => undefined} />)

    expect(screen.getByText("Steward")).toBeTruthy()
    expect(screen.queryByText("steward-worker")).toBeNull()
    expect(screen.queryByText("Services")).toBeNull()
  })

  it("has no border of its own once it is the head of the column, and stops before its edge", () => {
    // The first and the fourth correction, and both are invisible to jsdom as pixels but plain as
    // classes. The border: the grown island IS the head of the column, so a line under it cuts
    // the column in two. The width: at the full `--sidebar-width` this element lies on top of the
    // column's own right border, which is what Till saw as the island covering it.
    const { container } = render(<Island crumbs={CRUMBS} expanded onToggle={() => undefined} />)
    const head = container.firstElementChild as HTMLElement

    expect(head.className).toContain("border-0")
    expect(head.className).not.toContain("border-b-")
    expect(head.className).toContain("w-[calc(var(--sidebar-width)-1px)]")
  })
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
    withQueries(
      <UserMenu me={{ ...ME, discordAvatarUrl: "https://cdn.discordapp.com/a.png" }} />,
    )

    const trigger = screen.getByRole("button", { name: /Account/ })
    const img = trigger.querySelector("img")
    expect(img).toBeTruthy()
    expect(img?.getAttribute("src")).toBe("https://cdn.discordapp.com/a.png")
  })

  it("falls back to initials, and stays tappable, when the picture fails to load", () => {
    // The guard steward/91 asks for: a broken image is the same fallback as no field at all, not
    // a broken button.
    withQueries(
      <UserMenu me={{ ...ME, discordAvatarUrl: "https://cdn.discordapp.com/gone.png" }} />,
    )

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
