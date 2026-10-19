import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import type { ReactNode } from "react"
import { afterEach, expect, it, vi } from "vitest"

import type { Document, DocumentDeclaration, DocumentDraft } from "@/lib/api"
import { DocumentPreview } from "@/components/steward/message-editor/document-preview"

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
