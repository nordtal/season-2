import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ConfigEntry } from "@/lib/api"
import { TakePosition, type TakenPosition, takenValues } from "@/components/steward/game-groups/take-position"

function entry(key: string, more: Partial<ConfigEntry> = {}): ConfigEntry {
  return {
    path: key,
    key,
    label: key,
    explanation: "",
    noExplanationNeeded: true,
    filled: true,
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...more,
  }
}

const decimal = (key: string) => entry(key, { type: "DECIMAL" })
const whole = (key: string) => entry(key, { type: "INTEGER" })
const taken = { world: "nordtal", x: 106.5, y: 64, z: -88.12, yaw: 92.4, pitch: 12 }

describe("takenValues", () => {
  it("fills a spot exactly, its facing included, and only the keys the spot has", () => {
    const fields = [entry("world"), ...["x", "y", "z", "yaw"].map(decimal), entry("kind")]
    expect(takenValues("spot", fields, taken)).toEqual({
      world: "nordtal",
      x: "106.5",
      y: "64",
      z: "-88.12",
      yaw: "92.4",
    })
  })

  it("fills a centre's X and Z as the whole block the player stands in", () => {
    expect(takenValues("centre", [whole("x"), whole("z")], taken)).toEqual({ x: "106", z: "-89" })
  })

  it("fills the first corner of an area into its min keys and the second into its max keys", () => {
    const fields = [entry("world"), ...["min-x", "min-y", "min-z", "max-x", "max-y", "max-z"].map(whole)]
    const block = { ...taken, x: 10, y: 60, z: -4 }
    expect(takenValues("area", fields, block, 0)).toEqual({
      world: "nordtal",
      "min-x": "10",
      "min-y": "60",
      "min-z": "-4",
    })
    expect(takenValues("area", fields, block, 1)).toEqual({
      world: "nordtal",
      "max-x": "10",
      "max-y": "60",
      "max-z": "-4",
    })
  })
})

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } })
}

/** Steward reporting `taker`, smp answering a take with `run`, and the takes and withdrawals sent. */
function steward(taker: string, run: Record<string, unknown>) {
  const asked: unknown[] = []
  const withdrawn: string[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
      const method = init?.method ?? "GET"
      if (url === "/api/smp/take-position" && method === "GET") return json({ taker })
      if (url === "/api/smp/take-position" && method === "POST") {
        asked.push(JSON.parse(init?.body ?? "null"))
        return json({ id: "smp:9", status: "PENDING" })
      }
      if (url === "/api/commands/smp:9" && method === "DELETE") {
        withdrawn.push(url)
        return new Response(null, { status: 204 })
      }
      if (url === "/api/commands/smp:9") return json({ id: "smp:9", ...run })
      throw new Error(`the field asked for ${url}, which this test did not expect`)
    }),
  )
  return { asked, withdrawn }
}

function drawTake(shape: "spot" | "block" | "area", onTaken = vi.fn<(taken: TakenPosition, corner: 0 | 1) => void>()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <TakePosition shape={shape} disabled={false} onTaken={onTaken} />
    </QueryClientProvider>,
  )
  return onTaken
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("TakePosition", () => {
  it("is off and says why while the admin has no linked player", async () => {
    steward("UNLINKED", { status: "DONE" })
    drawTake("block")
    expect(await screen.findByText("Link a Minecraft account to your Discord account first.")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Take the block I look at" })).toHaveProperty("disabled", true)
  })

  it("is off and says why while the admin's player is not on smp", async () => {
    steward("ELSEWHERE", { status: "DONE" })
    drawTake("spot")
    expect(await screen.findByText("Your linked player is not on SMP.")).toBeTruthy()
    expect(screen.getByRole("button", { name: "Take my position" })).toHaveProperty("disabled", true)
  })

  it("asks for where the player stands on a spot, and hands the answer on", async () => {
    const answer = { world: "nordtal", x: 1.5, y: 64, z: -2.25, yaw: 90, pitch: 0 }
    const { asked } = steward("READY", { status: "DONE", answer })
    const onTaken = drawTake("spot")
    const take = screen.getByRole("button", { name: "Take my position" })
    await waitFor(() => expect(take).toHaveProperty("disabled", false))
    fireEvent.click(take)
    await waitFor(() => expect(onTaken).toHaveBeenCalledWith(answer, 0))
    expect(asked).toEqual([{ block: false }])
  })

  it("offers both corners of an area and names which one was taken", async () => {
    const answer = { world: "nordtal", x: 1, y: 64, z: -2, yaw: 0, pitch: 0 }
    const { asked } = steward("READY", { status: "DONE", answer })
    const onTaken = drawTake("area")
    const second = screen.getByRole("button", { name: "Take corner 2" })
    await waitFor(() => expect(second).toHaveProperty("disabled", false))
    fireEvent.click(second)
    await waitFor(() => expect(onTaken).toHaveBeenCalledWith(answer, 1))
    expect(asked).toEqual([{ block: true }])
  })

  it("turns a waiting take into Cancel, which withdraws it and hands nothing on", async () => {
    const { withdrawn } = steward("READY", { status: "RUNNING" })
    const onTaken = drawTake("block")
    const take = screen.getByRole("button", { name: "Take the block I look at" })
    await waitFor(() => expect(take).toHaveProperty("disabled", false))
    fireEvent.click(take)
    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }))
    await waitFor(() => expect(withdrawn).toEqual(["/api/commands/smp:9"]))
    expect(onTaken).not.toHaveBeenCalled()
  })

  it("withdraws a waiting take when the field closes", async () => {
    const { withdrawn } = steward("READY", { status: "RUNNING" })
    drawTake("spot")
    const take = screen.getByRole("button", { name: "Take my position" })
    await waitFor(() => expect(take).toHaveProperty("disabled", false))
    fireEvent.click(take)
    await screen.findByRole("button", { name: "Cancel" })
    cleanup()
    await waitFor(() => expect(withdrawn).toEqual(["/api/commands/smp:9"]))
  })
})
