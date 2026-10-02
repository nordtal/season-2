import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * A page learns about a change from the live stream; the only timed read left is the minute's reconciliation.
 *
 * So every `refetchInterval` names `RECONCILE`, and nothing that talks to steward waits on a timer of its own.
 */
function sources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) return sources(full)
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [full] : []
  })
}

const TALKS_TO_STEWARD = /\bapi<|\bfetch\(/
const OWN_TIMER = /setInterval\(|setTimeout\(\s*resolve/

describe("nothing polls", () => {
  const files = sources(source).map((file) => ({
    name: path.relative(source, file),
    text: readFileSync(file, "utf8"),
  }))

  it("reads every query again no more often than the reconciliation", () => {
    const fast = files.flatMap(({ name, text }) =>
      [...text.matchAll(/refetchInterval/g)]
        .filter((match) => !text.slice(match.index, match.index + 400).includes("RECONCILE"))
        .map(() => name),
    )
    expect(fast).toEqual([])
  })

  it("keeps no timer of its own where steward is asked", () => {
    const polling = files.filter(({ text }) => TALKS_TO_STEWARD.test(text) && OWN_TIMER.test(text))
    expect(polling.map(({ name }) => name)).toEqual([])
  })
})
