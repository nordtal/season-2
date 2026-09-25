import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * There is one way to wait in this interface, and it goes through `QueryState` (steward/120).
 *
 * Till, 2026-09-19: everywhere in the app that loads data, there has to be a skeleton behind it,
 * and one single approach for the whole app rather than one per page. That approach is
 * `components/steward/query-state.tsx`: it calls its child twice, once with `undefined` and once
 * with the data, so a view writes its layout once and gets its own waiting shape out of it. What
 * this file guards is that the approach stays the only one - three patterns living side by side is
 * how the interface got here in the first place (measured 2026-09-19: fifteen declarative gates,
 * nineteen hand-written ternary chains, nine files with no waiting state at all).
 *
 * <h2>What the ticket asked for, and what is here instead</h2>
 * The plan's second assertion was that `isPending ?` must not appear outside `query-state.tsx` at
 * all. That was written before it was noticed that `isPending` is also a **mutation's** word for
 * "this button is busy": `"Saving…"`, `"Switching…"`, `"Waiting for the key…"`, `"Searching…"` -
 * seven places where it is the honest word and no loading state at all. Outlawing the string would
 * either need seven exemptions that all say the same nothing, or force those buttons to lie.
 *
 * So the rule is on the two boxes instead. `Loading` is the flat grey block the ticket exists to
 * replace, and a view that still draws one either went through the gate's `rows` opt-out - which
 * is inside `QueryState` and therefore invisible here, by design - or is named below with its
 * reason. `Failure` is deliberately **not** guarded: a mutation that fails needs an error line and
 * has no query to hang it on, which is seventeen honest call sites.
 *
 * <h2>The caveat, taken word for word from `fits-on-a-phone.test.ts:22-30`</h2>
 * **THIS FILE CANNOT TELL YOU THAT THE INTERFACE FITS. It can only tell you that a rule which once
 * made it fit has not been deleted.** The only instrument that sees a width is a picture:
 * `/home/dev/ui-shots/tool/preview.mjs --target=/access --width=390 --out=…` renders the built
 * frontend against the fixtures in `/home/dev/ui-shots/fixtures` with no server and no session,
 * and `overflow.mjs` measures the same thing in numbers when a deployment is running. A guard that
 * is trusted further than it can see is worse than no guard - so whether the skeleton actually
 * looks like what follows it is an eye's question, and the eye is Till's.
 */
const QUERIES = /from "@\/lib\/queries"/
const GATE = /from "@\/components\/steward\/query-state"/
const FLAT_BARS = /<Loading\b/

/**
 * Files that read from `@/lib/queries` and draw no waiting shape, each with the reason.
 *
 * By file rather than by line, the same trade `no-middle-dot.test.ts` explains: a file half swept
 * looks exactly like one this test never read. Three of the four below only ever call *mutations*,
 * which have nothing to wait for; the fourth fetches one thing and hands it to a component that
 * carries its own waiting state.
 */
const NO_WAITING_SHAPE = new Map<string, string>([
  [
    "app/app-sidebar.tsx",
    "steward/120: every label comes from navigation.ts. The one fetched thing is the health dot," +
      " and HealthDot in components/steward/status.tsx draws its own skeleton for it.",
  ],
  [
    "components/steward/console.tsx",
    "useConsole is a mutation. The log is a stream, not a query, and says" +
      " \"Waiting for the log\" in its own window until the first line arrives.",
  ],
  ["app/hold-key.tsx", "steward/120: useHoldKey is a mutation. Nothing here is read."],
  ["app/security-key.tsx", "steward/120: useRegisterKey is a mutation. Nothing here is read."],
  [
    "app/step-up.tsx",
    "steward/120: useMe is read for one number in a sentence, behind a default of five minutes." +
      " There is no surface to reserve.",
  ],
])

/**
 * The views that still draw flat grey bars, each with the reason. This is the ticket's own escape
 * clause: where one view genuinely cannot draw itself without its data, that one view keeps a
 * second shape - and the exception belongs in a map here, under its ticket number, rather than in
 * nobody's notes.
 */
const FLAT_BARS_ALLOWED = new Map<string, string>([
  [
    "pages/operations.tsx",
    "steward/120: the run being looked up is one row out of a list, so there is no shape to draw" +
      " until it is known which run it is. The card holds its height instead.",
  ],
  [
    "pages/backups.tsx",
    "steward/120: destination and schedule are forms whose field set comes from the worker's own" +
      " config document. A form cannot draw fields it does not know the names of yet.",
  ],
  [
    "pages/updates.tsx",
    "The update schedule is the same kind of form as the backup one: its fields come from the" +
      " worker's own config document.",
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

describe("one waiting state, and it is QueryState (steward/120)", () => {
  it("has no view that fetches without importing the thing that draws the wait", () => {
    expect(
      withoutTheGate(),
      "These files read from @/lib/queries and never import @/components/steward/query-state, so" +
        " whatever they fetch appears out of nothing. Draw the waiting shape through QueryState -" +
        " or, if there is genuinely nothing to reserve, say so in NO_WAITING_SHAPE with the" +
        " reason.\n\n" + withoutTheGate().join("\n"),
    ).toEqual([])
  })

  it("has no view drawing flat grey bars of its own", () => {
    expect(
      drawingFlatBars(),
      "These files draw <Loading> themselves. That is the block steward/120 replaced: it knows" +
        " nothing about the layout under it, so it cannot resemble it. Pass the shape to" +
        " QueryState instead - or, if the shape is genuinely unknowable before the data (a form" +
        " whose fields come from the answer), name the file in FLAT_BARS_ALLOWED with the" +
        " reason.\n\n" + drawingFlatBars().join("\n"),
    ).toEqual([])
  })

  /**
   * Without this, a broken path or a changed extension makes both rules above pass by finding
   * nothing at all - a green build and a guard nobody can trust. Same shape as the second test in
   * `no-middle-dot.test.ts`.
   */
  it("actually reads the sources, so an empty result means something", () => {
    const all = views()
    expect(all.length).toBeGreaterThan(40)
    expect(all.filter(({ text }) => QUERIES.test(text)).length).toBeGreaterThan(20)
    expect(all.some(({ relative }) => relative === "components/steward/query-state.tsx")).toBe(true)
  })

  /**
   * An exemption for a file that no longer needs one is a promise nobody is keeping. Both maps are
   * checked the same way `no-middle-dot.test.ts` checks its own.
   */
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
