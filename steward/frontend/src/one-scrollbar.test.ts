import { readFileSync, readdirSync, statSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

/**
 * Every scroll container draws the same thin scrollbar in the border colour, natively and in `ScrollArea`.
 *
 * A class that hides or restyles a scrollbar in one place is a second style.
 */
function files(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const full = path.join(directory, name)
    if (statSync(full).isDirectory()) return files(full)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [full] : []
  })
}

describe("one scrollbar style", () => {
  it("keeps the rule every native scrollbar follows", () => {
    const css = readFileSync(path.join(source, "index.css"), "utf8")
    expect(css).toMatch(/scrollbar-width:\s*thin/)
    expect(css).toMatch(/scrollbar-color:\s*var\(--border\) transparent/)
  })

  it("has no class that hides or restyles a scrollbar anywhere else", () => {
    const offenders = files(source).filter((file) =>
      /no-scrollbar|scrollbar-none|scrollbar-hide|\[scrollbar-|\[&::-webkit-scrollbar/.test(readFileSync(file, "utf8")),
    )
    expect(offenders.map((file) => path.relative(source, file))).toEqual([])
  })

  it("draws ScrollArea's thumb in the same colour", () => {
    const area = readFileSync(path.join(source, "components/ui/scroll-area.tsx"), "utf8")
    expect(area).toMatch(/ScrollAreaThumb[\s\S]*bg-border/)
  })
})
