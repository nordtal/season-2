import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { assert, describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Every view that loads data waits through `QueryState`, the one skeleton approach.
 *
 * Only `Loading` is guarded; `isPending` and `Failure` also serve mutations, where they are honest.
 */
const QUERIES = /from "@\/lib\/queries"/
const GATE = /from "@\/components\/steward\/query-state"/
const FLAT_BARS = /<Loading\b/

/** Files that read from `@/lib/queries` and draw no waiting shape, each with the reason, listed by file. */
const NO_WAITING_SHAPE = new Map<string, string>([
  [
    "app/app-sidebar.tsx",
    "Every label but the services' comes from navigation.ts. The service rows wait for /api/topology as" +
      " skeleton rows of their own shape, and HealthDot in components/steward/status.tsx draws its own.",
  ],
  [
    "components/steward/recreate.tsx",
    "useAskForRun is a mutation, and useAgent only gates the button, whose title says when its state is not" +
      " known yet. Nothing here waits to draw.",
  ],
  [
    "components/steward/console.tsx",
    "useConsole is a mutation. The log is a stream, not a query, and says" +
      ' "Waiting for the log" in its own window until the first line arrives.',
  ],
  ["app/hold-key.tsx", "useHoldKey is a mutation. Nothing here is read."],
  [
    "pages/access-person.tsx",
    "The dialogs' hooks are mutations. usePeople and useMe only decide whether Revoke admin is offered, and the" +
      " pages that draw a person wait for the roster before they do.",
  ],
  [
    "components/steward/group-form.tsx",
    "Hooks over a settings group and a day picker; the dialogs that call them draw the wait.",
  ],
  ["app/security-key.tsx", "useRegisterKey is a mutation. Nothing here is read."],
  [
    "components/steward/message-preview.tsx",
    "The tones and glyphs only colour a line of text drawn from the start; until they arrive it is drawn plain.",
  ],
  [
    "components/steward/group-draft.tsx",
    "useSaveConfig is a mutation. The group it drafts was read by the editor that calls it, which draws the wait.",
  ],
  [
    "app/step-up.tsx",
    "useMe is read for one number in a sentence, behind a default of five minutes. There is no" +
      " surface to reserve.",
  ],
])

/** The views that still draw flat grey bars, each with the reason. */
const FLAT_BARS_ALLOWED = new Map<string, string>([
  [
    "pages/operations.tsx",
    "The run being looked up is one row out of a list, so there is no shape to draw until it is" +
      " known which run it is. The card holds its height instead.",
  ],
  [
    "pages/backup-dialogs.tsx",
    "Destination and schedule are forms whose field set comes from steward's own config" +
      " document. A form cannot draw fields it does not know the names of yet.",
  ],
  [
    "pages/updates.tsx",
    "The update schedule is the same kind of form as the backup one: its fields come from the" +
      " steward's own config document.",
  ],
])

function sourceFiles(directory: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      found.push(...sourceFiles(full))
    } else if (entry.name.endsWith(".tsx") && !entry.name.includes(".test.")) {
      found.push(full)
    }
  }
  return found
}

/** Only `.tsx`: a `.ts` file has no layout, so there is nothing in one for a skeleton to be. */
function views(): { relative: string; text: string }[] {
  return sourceFiles(source).map((file) => ({
    relative: path.relative(source, file),
    text: readFileSync(file, "utf8"),
  }))
}

function withoutTheGate(): string[] {
  return views()
    .filter(({ relative }) => !NO_WAITING_SHAPE.has(relative))
    .filter(({ text }) => QUERIES.test(text) && !GATE.test(text))
    .map(({ relative }) => relative)
}

function drawingFlatBars(): string[] {
  return views()
    .filter(({ relative }) => relative !== "components/steward/query-state.tsx")
    .filter(({ relative }) => !FLAT_BARS_ALLOWED.has(relative))
    .filter(({ text }) => FLAT_BARS.test(text))
    .map(({ relative }) => relative)
}

describe("one waiting state, and it is QueryState", () => {
  it("has no view that fetches without importing the thing that draws the wait", () => {
    assert.deepEqual(
      withoutTheGate(),
      [],
      "These files read from @/lib/queries and never import @/components/steward/query-state, so" +
        " whatever they fetch appears out of nothing. Draw the waiting shape through QueryState -" +
        " or, if there is genuinely nothing to reserve, say so in NO_WAITING_SHAPE with the" +
        " reason.\n\n" +
        withoutTheGate().join("\n"),
    )
  })

  it("has no view drawing flat grey bars of its own", () => {
    assert.deepEqual(
      drawingFlatBars(),
      [],
      "These files draw <Loading> themselves, which knows nothing about the layout under it, so" +
        " it cannot resemble it. Pass the shape to QueryState instead - or, if the shape is" +
        " genuinely unknowable before the data (a form whose fields come from the answer), name" +
        " the file in FLAT_BARS_ALLOWED with the reason.\n\n" +
        drawingFlatBars().join("\n"),
    )
  })

  /** Without this, a broken path or extension makes both rules above pass by finding nothing. */
  it("actually reads the sources, so an empty result means something", () => {
    const all = views()
    expect(all.length).toBeGreaterThan(40)
    expect(all.filter(({ text }) => QUERIES.test(text)).length).toBeGreaterThan(20)
    expect(all.some(({ relative }) => relative === "components/steward/query-state.tsx")).toBe(true)
  })

  /** An exemption a file no longer needs is stale. */
  it("has no exemption that is already stale", () => {
    const stale: string[] = []
    const byName = new Map(views().map((view) => [view.relative, view.text]))
    for (const [relative, reason] of NO_WAITING_SHAPE) {
      const text = byName.get(relative)
      if (text === undefined) stale.push(`${relative} (${reason}) no longer exists`)
      else if (!QUERIES.test(text)) {
        stale.push(`${relative} (${reason}) no longer reads anything - drop the exemption`)
      } else if (GATE.test(text)) {
        stale.push(`${relative} (${reason}) goes through the gate now - drop the exemption`)
      }
    }
    for (const [relative, reason] of FLAT_BARS_ALLOWED) {
      const text = byName.get(relative)
      if (text === undefined) stale.push(`${relative} (${reason}) no longer exists`)
      else if (!FLAT_BARS.test(text)) {
        stale.push(`${relative} (${reason}) draws no flat bars any more - drop the exemption`)
      }
    }
    expect(stale).toEqual([])
  })
})
