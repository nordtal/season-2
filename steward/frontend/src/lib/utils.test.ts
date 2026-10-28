import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

import { cn } from "@/lib/utils"

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..")

describe("cn", () => {
  it("keeps the colour beside a text size of the theme", () => {
    expect(cn("text-xs text-muted-foreground", "text-badge")).toBe("text-muted-foreground text-badge")
    expect(cn("text-sm", "text-caption")).toBe("text-caption")
    expect(cn("text-foreground", "text-inline")).toBe("text-foreground text-inline")
  })

  it("lets a width or a height of the theme win over an earlier one", () => {
    expect(cn("max-w-sm", "max-w-page")).toBe("max-w-page")
    expect(cn("max-h-96", "max-h-list")).toBe("max-h-list")
  })

  /** A component that imports `cn` from the package would merge without the theme's tokens. */
  it("is the only way a source file imports the package", () => {
    const importers = readdirSync(source, { recursive: true, encoding: "utf8" })
      .filter((file) => /\.tsx?$/.test(file) && !/\.test\.tsx?$/.test(file))
      .filter((file) => /from "cn(\/[\w-]+)?"/.test(readFileSync(path.join(source, file), "utf8")))
    expect(importers).toEqual(["lib/utils.ts"])
  })
})
