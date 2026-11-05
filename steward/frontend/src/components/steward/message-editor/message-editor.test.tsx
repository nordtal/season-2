import { cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { MessageEntry } from "@/lib/api"
import { queryResult } from "@/lib/query-fixtures"
import { MessageEditor } from "@/components/steward/message-editor/message-editor"

vi.mock("@/lib/queries", () => ({
  useGameAction: () => ({ mutate: vi.fn<() => void>(), isPending: false, isError: false, error: null }),
  useGlyphs: () => queryResult([]),
  useMessageCheck: () => queryResult([]),
  useMessageExamples: () => queryResult(undefined),
  useMessageSyntax: () => queryResult(undefined),
  useMessageTexts: () => queryResult(undefined),
}))

afterEach(() => {
  cleanup()
  document.body.replaceChildren()
})

function steward(key: string, text: string): MessageEntry {
  return {
    bundle: "steward",
    key,
    inBundle: true,
    texts: { en: [text] },
    overrides: {},
    args: [],
    section: [],
    format: "PLAIN",
    shown: [],
    limit: 0,
  }
}

/** Draws the editor of one text and returns the frame its preview column shows it in. */
function frameOf(entry: MessageEntry): string | null {
  const column = document.createElement("div")
  document.body.append(column)
  render(
    <MessageEditor
      entry={entry}
      path="/api/messages"
      colours={{}}
      places={{}}
      draft={undefined}
      highlight={null}
      onChange={() => {}}
      fallbacks={[]}
      onTakeOver={() => {}}
      takingOver={false}
      language="en"
      previewsIn={column}
    />,
  )
  const frames = [...column.querySelectorAll<HTMLElement>("[data-frame]")]
  expect(frames).toHaveLength(1)
  return frames[0].dataset.frame ?? null
}

describe("the message editor's preview", () => {
  it("draws a text only the server answers with as the toast Steward shows it in", () => {
    expect(frameOf(steward("steward.answer.already-admin", "Already an admin."))).toBe("toast")
  })

  it("draws any other text of Steward's on a page", () => {
    expect(frameOf(steward("steward.announcements.ask", "Send it?"))).toBe("page")
  })
})
