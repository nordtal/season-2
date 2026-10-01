import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

import config from "../vite.config"

const here = path.dirname(fileURLToPath(import.meta.url))

/** A path handed to `api()`, which is the one function in this frontend that calls `fetch`. */
const CALL = /\bapi(?:<[^>]*>)?\(\s*[`"'](\/[^`"']*)/g

/** A real navigation. `<Link to=...>` is the router and never leaves the browser. */
const NAVIGATION = /<a\s+href="(\/[^"]*)"/g

function sources(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) return sources(full)
    if (!/\.tsx?$/.test(entry.name) || /\.test\.tsx?$/.test(entry.name)) return []
    return [full]
  })
}

/** The first segment of a path: `/api/services/smp` is proxied by an entry for `/api`. */
function prefixOf(calledPath: string): string {
  return `/${calledPath.split("/")[1] ?? ""}`
}

function called(): Map<string, string> {
  const found = new Map<string, string>()
  for (const file of sources(path.join(here))) {
    const text = readFileSync(file, "utf8")
    for (const pattern of [CALL, NAVIGATION]) {
      pattern.lastIndex = 0
      let hit = pattern.exec(text)
      while (hit) {
        found.set(prefixOf(hit[1]), `${path.relative(here, file)} calls ${hit[1]}`)
        hit = pattern.exec(text)
      }
    }
  }
  return found
}

/** `npm run dev` proxies every prefix the interface calls; a missing one gets Vite's HTML fallback with a 200. */
describe("the dev server", () => {
  it("proxies every prefix the interface calls", () => {
    const proxy = config.server?.proxy ?? {}
    const proxied = Object.keys(proxy)

    const missing = [...called()]
      .filter(([prefix]) => !proxied.some((entry) => entry === prefix || prefix.startsWith(entry)))
      .map(([, where]) => where)

    expect(missing).toEqual([])
  })

  it("finds both of the prefixes this interface has", () => {
    /** Without this, a call shape the regexes do not know passes as nothing to proxy. */
    expect([...called().keys()].toSorted()).toEqual(["/api", "/auth"])
  })
})
