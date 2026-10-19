import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, expect, it, vi } from "vitest"

import type { Document, DocumentDeclaration, DocumentDraft } from "@/lib/api"
import { DocumentPreview, galleryCells } from "@/components/steward/message-editor/document-preview"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const declaration: DocumentDeclaration = {
  namespace: "discord",
  name: "greeting",
  area: "Greeting",
  shape: "MESSAGE",
  values: {},
  slots: [],
}

function saying(words: string): Document {
  return { parts: [{ type: "TEXT", lines: [{ text: { en: words } }] }] }
}

it("asks for a drawing once typing pauses, and an older draft's late answer never replaces a newer one", async () => {
  const waiting = new Map<string, (response: Response) => void>()
  const asked: string[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, init?: { body?: string }) => {
      const draft: DocumentDraft = JSON.parse(init?.body ?? "")
      const line = String(draft.document.parts[0]?.lines?.[0]?.text.en)
      asked.push(line)
      return new Promise<Response>((resolve) => waiting.set(line, resolve))
    }),
  )
  const answer = (line: string) =>
    waiting.get(line)!(
      new Response(JSON.stringify({ discord: { components: [{ type: 10, content: line }] } }), {
        headers: { "Content-Type": "application/json" },
      }),
    )

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const { rerender } = render(<DocumentPreview declaration={declaration} game={false} document={saying("first")} />, {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  })
  await vi.waitFor(() => expect(asked).toEqual(["first"]))
  rerender(<DocumentPreview declaration={declaration} game={false} document={saying("second")} />)
  rerender(<DocumentPreview declaration={declaration} game={false} document={saying("third")} />)
  await vi.waitFor(() => expect(asked).toEqual(["first", "third"]))

  answer("third")
  await screen.findByText("third")
  answer("first")
  await new Promise((resolve) => setTimeout(resolve, 50))
  expect(screen.queryByText("first")).toBeNull()
  expect(screen.getByText("third")).toBeTruthy()
})

it("draws a gallery's one image whole at its own shape, never cropped to a banner", async () => {
  const image = "https://nordtal.eu/square.png"
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(JSON.stringify({ discord: { components: [{ type: 12, items: [{ media: { url: image } }] }] } }), {
          headers: { "Content-Type": "application/json" },
        }),
    ),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const { container } = render(<DocumentPreview declaration={declaration} game={false} document={saying("")} />, {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  })
  await vi.waitFor(() => expect(container.querySelector(`img[src="${image}"]`)).not.toBeNull())
  const drawn = container.querySelector(`img[src="${image}"]`)!.className
  expect(drawn).toMatch(/\bw-auto\b/)
  expect(drawn).toMatch(/\bh-auto\b/)
  expect(drawn).not.toMatch(/aspect-|\bh-28\b/)
})

it("lays a gallery of two to ten images out in whole rows, the rows of three under a first row of the rest", () => {
  for (let count = 2; count <= 10; count++) {
    const cells = galleryCells(count)
    expect(cells).toHaveLength(count)
    // A cell spanning two rows counts twice.
    const area = cells.reduce((sum, cell) => {
      const span = Number(/col-span-(\d)/.exec(cell)![1])
      return sum + span * (cell.includes("row-span-2") ? 2 : 1)
    }, 0)
    expect(area % 6, `${count} images`).toBe(0)
  }
  expect(galleryCells(5).map((cell) => cell.split(" ")[0])).toEqual([
    "col-span-3",
    "col-span-3",
    "col-span-2",
    "col-span-2",
    "col-span-2",
  ])
  expect(galleryCells(7)[0]).toContain("col-span-6")
  expect(galleryCells(9).every((cell) => cell.startsWith("col-span-2"))).toBe(true)
})
