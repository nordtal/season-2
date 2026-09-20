import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { describe, expect, it } from "vitest"

import config from "../vite.config"

/**
 * Every prefix this interface calls is a prefix `npm run dev` hands to the backend.
 *
 * `vite.config.ts` proxied `/api` and nothing else, and the interface talks to **two** prefixes:
 * 33 calls under `/api` and seven under `/auth` - `POST /auth/logout`, the four WebAuthn steps and
 * the `<a href="/auth/login">` on the sign-in page. Under the dev server those seven landed in
 * Vite's SPA fallback, which answers `index.html` with a 200, so nothing looked broken and nothing
 * worked: `Gate` wants a held key for every read and a fresh one for every write, so a dev server
 * that cannot sign in and cannot hold a key has no usable page at all (season-2-ops/144).
 *
 * That is why this is a test rather than one more line in the config. A third prefix - and the
 * `/auth` family was itself the second - would fail exactly as quietly as the second one did: no
 * error, no 404, a page of HTML where JSON was expected. This is the dev-server half of
 * `EveryCalledPathIsRoutedTest`, which asks the same question of the Java routes.
 *
 * It reads the real config object rather than the file's text, so a proxy written in any of Vite's
 * shapes counts.
 */

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
function prefixOf(called: string): string {
  return `/${called.split("/")[1] ?? ""}`
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
    // Without this the test above passes on an empty scrape, which is the way a guard like this
    // dies: a call written in a shape the regexes do not know reads as "nothing to proxy".
    expect([...called().keys()].sort()).toEqual(["/api", "/auth"])
  })
})
