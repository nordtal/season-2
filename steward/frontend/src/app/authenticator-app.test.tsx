import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { Me } from "@/lib/api"
import { AuthenticatorAppRow, useAuthenticatorActions } from "@/app/authenticator-app"
import { HoldKeyPage } from "@/app/hold-key"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const KEY = { id: "k1", label: "YubiKey", registeredAt: "2026-09-01T10:00:00Z" }
const APP = { addedAt: "2026-10-01T10:00:00Z" }

function meWith(change: Partial<Me>): Me {
  return {
    signedIn: true,
    id: "214906139328839681",
    name: "ally",
    csrf: "t",
    relyingPartyId: "nordtal.eu",
    stepUpHolds: [],
    authenticatorOffered: true,
    verified: false,
    keys: [],
    ...change,
  }
}

function within(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}>{node}</QueryClientProvider>)
}

function Row({ me }: { me: Me }) {
  return <AuthenticatorAppRow state={useAuthenticatorActions(me)} />
}

describe("the door", () => {
  it("offers the key first and the code below it", () => {
    within(<HoldKeyPage me={meWith({ keys: [KEY], authenticator: APP })} />)

    const key = screen.getByRole("button", { name: "Use my key" })
    const code = screen.getByRole("button", { name: "Use code" })
    expect(key.compareDocumentPosition(code) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("lets an account with only the app in by its code", async () => {
    const calls: { url: string; body: unknown }[] = []
    vi.stubGlobal("fetch", async (url: string, init?: RequestInit) => {
      calls.push({ url, body: typeof init?.body === "string" ? JSON.parse(init.body) : null })
      return new Response(null, { status: 204 })
    })
    within(<HoldKeyPage me={meWith({ authenticator: APP })} />)
    expect(screen.queryByRole("button", { name: "Use my key" })).toBeNull()

    fireEvent.change(screen.getByLabelText("Code from the app"), { target: { value: "123 456" } })
    fireEvent.click(screen.getByRole("button", { name: "Use code" }))

    await waitFor(() => expect(calls).toContainEqual({ url: "/auth/totp/verify", body: { code: "123 456" } }))
  })
})

describe("the popover's app row", () => {
  it("cannot remove the app while it is the only factor", () => {
    within(<Row me={meWith({ verified: true, authenticator: APP })} />)
    expect(screen.getByRole("button", { name: "Remove the authenticator app" }).hasAttribute("disabled")).toBe(true)
  })

  it("can remove it beside a key", () => {
    within(<Row me={meWith({ verified: true, keys: [KEY], authenticator: APP })} />)
    expect(screen.getByRole("button", { name: "Remove the authenticator app" }).hasAttribute("disabled")).toBe(false)
  })
})
