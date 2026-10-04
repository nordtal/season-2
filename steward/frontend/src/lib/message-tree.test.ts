import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

import { parseText, readText, UnreadableText, writeText } from "@/lib/message-tree"
import type { TextNode } from "@/lib/texts"

/** The vectors Java's parser is held to, so both read a text to one tree. */
type Vector = { text: string; markup?: boolean; nodes: TextNode[] }

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../..")
const vectors: Vector[] = JSON.parse(
  readFileSync(path.join(repository, "messages/src/test/resources/web-target.json"), "utf8"),
)

describe("the browser's parser", () => {
  it("has tag vectors among the shared ones", () => {
    expect(vectors.filter((vector) => vector.markup).length).toBeGreaterThanOrEqual(5)
  })

  it.each(vectors.map((vector) => [vector.text, vector] as const))("reads %s to Java's tree", (_, vector) => {
    expect(parseText(vector.text, vector.markup ?? false)).toEqual(vector.nodes)
  })

  it.each(vectors.map((vector) => [vector.text, vector] as const))("writes %s back to the same tree", (_, vector) => {
    const markup = vector.markup ?? false
    expect(parseText(writeText(vector.nodes, markup), markup)).toEqual(vector.nodes)
  })

  it("says where a text stops being readable, as the caret counts", () => {
    const error = thrown(() => parseText("Hi {name", false))
    expect(error).toBeInstanceOf(UnreadableText)
    expect(error).toHaveProperty("at", 8)
    expect(readText("{n, plural, one {#}}", false)).toBeNull()
    expect(readText("a } b", false)).toBeNull()
  })

  it("keeps a < that opens no tag as text, and escapes what would read as syntax", () => {
    expect(parseText("a <b", true)).toEqual(["a <b"])
    expect(writeText(["{x} <y> #"], true)).toBe("\\{x\\} \\<y> #")
    expect(writeText(["{x} <y>"], false)).toBe("\\{x\\} <y>")
  })
})

function thrown(run: () => unknown): unknown {
  try {
    run()
  } catch (error) {
    return error
  }
  return undefined
}
