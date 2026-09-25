import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { Actor, Entity, entityKind } from "@/components/steward/entity"
import { IDENTIFIER_PATTERN } from "@/components/steward/identity"

/**
 * Till, 2026-09-20: one component that recognises an entity and draws it properly, app-wide. These
 * hold the four answers it gives - a person by Discord id, a person by Minecraft UUID, a service,
 * and the question mark for everything it cannot place.
 */

const DISCORD_ID = "214906139328839681"
const MC_UUID = "11111111-2222-3333-4444-555555555555"

const PEOPLE = [
  {
    discordId: DISCORD_ID,
    discordDisplayName: "Ally",
    minecraftUuid: MC_UUID,
    mcName: "AliceMC",
  },
]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function draw(node: ReactNode) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/people") return json(200, PEOPLE)
      if (url === "/api/settings") return json(200, { minecraftHeadBaseUrl: "" })
      throw new Error(`the entity asked for ${url}, which this test did not expect`)
    }),
  )
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const root = createRootRoute({ component: () => <>{node}</> })
  const service = createRoute({ getParentRoute: () => root, path: "/services/$name" })
  const router = createRouter({
    routeTree: root.addChildren([service]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("entityKind - what an identifier is, from its shape", () => {
  it("tells the four kinds apart", () => {
    expect(entityKind(DISCORD_ID)).toBe("discord")
    expect(entityKind(MC_UUID)).toBe("minecraft")
    expect(entityKind(MC_UUID.replace(/-/g, ""))).toBe("minecraft")
    expect(entityKind("smp")).toBe("service")
    expect(entityKind("steward-worker")).toBe("service")
    expect(entityKind("host")).toBe("unknown")
    expect(entityKind("")).toBe("unknown")
  })
})

describe("Entity", () => {
  it("draws a Discord id as the person, never as the number", async () => {
    draw(<Entity id={DISCORD_ID} />)

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("draws a Minecraft UUID as the Minecraft name", async () => {
    draw(<Entity id={MC_UUID} />)

    expect(await screen.findByText("AliceMC")).toBeTruthy()
    expect(IDENTIFIER_PATTERN.test(document.body.textContent ?? "")).toBe(false)
  })

  it("finds the person behind an undashed UUID as well", async () => {
    draw(<Entity id={MC_UUID.replace(/-/g, "")} />)

    expect(await screen.findByText("AliceMC")).toBeTruthy()
  })

  it("keeps an unknown Discord id readable without printing it", async () => {
    draw(<Entity id="300000000000000002" />)

    expect(await screen.findByText("no Discord name on record")).toBeTruthy()
    expect(document.body.textContent).not.toContain("300000000000000002")
  })

  it("draws a service as a link to its page", async () => {
    draw(<Entity id="smp" />)

    const link = await screen.findByRole("link", { name: "smp" })
    expect(link.getAttribute("href")).toBe("/services/smp")
  })

  it("marks what it cannot place with a question mark", async () => {
    draw(<Entity id="token-rotation-check" />)

    const text = await screen.findByText("token-rotation-check")
    expect(text.closest("[data-entity='unknown']")).toBeTruthy()
  })

  it("lets the caller name the kind when the shape is ambiguous", async () => {
    draw(<Entity id="smp" kind="discord" />)

    expect(await screen.findByText("no Discord name on record")).toBeTruthy()
  })
})

describe("Actor - who asked for a run", () => {
  it("is Steward for the nightly clock, the person for an id, and unknown for a bare label", async () => {
    draw(
      <>
        <Actor system discordId="" label="" />
        <Actor system={false} discordId={DISCORD_ID} label="" />
        <Actor system={false} discordId="" label="agent (plane session)" />
      </>,
    )

    expect(await screen.findByText("Ally")).toBeTruthy()
    expect(screen.getByText("Steward")).toBeTruthy()
    expect(
      screen.getByText("agent (plane session)").closest("[data-entity='unknown']"),
    ).toBeTruthy()
  })
})
