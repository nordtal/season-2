import { readdirSync, readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { icons as pixelarticons } from "@iconify-json/pixelarticons"
import { assert, describe, expect, it } from "vitest"

import { PIXELARTICON_PATHS, PIXELARTICONS } from "@/components/ui/icons"

const SOURCE = path.dirname(fileURLToPath(import.meta.url))
interface Package {
  dependencies?: Record<string, string>
  devDependencies?: Record<string, string>
}
const PACKAGE: Package = JSON.parse(readFileSync(path.join(SOURCE, "..", "package.json"), "utf8"))
const STYLES = readFileSync(path.join(SOURCE, "index.css"), "utf8")

/** The one module that draws icons. */
const ICONS = "components/ui/icons.tsx"

/** The packages that carry a typeface or an icon, which are only these. */
const FACE_AND_ICON_PACKAGES = [
  "@fontsource-variable/inter",
  "@fontsource-variable/outfit",
  "@iconify-json/pixelarticons",
]

/** A package name that carries a typeface or an icon set. */
const FACE_OR_ICON =
  /(^@fontsource|font|icon|^lucide|^@phosphor|^@tabler|^@heroicons|^@radix-ui\/react-icons|^react-feather)/i

/** The files that draw an `<svg>` of their own: the icons, a brand's logo, and pictures that are not icons. */
const OWN_SVG = new Set([
  ICONS,
  "app/discord-mark.tsx",
  "components/steward/network/wires.tsx",
  "components/steward/qr-code.tsx",
])

/** A face or an icon fetched while Steward runs, instead of bundled. */
const RUNTIME_FETCH =
  /fonts\.(?:googleapis|gstatic)\.com|api\.iconify\.design|use\.typekit|cdn\.jsdelivr\.net|unpkg\.com|cdnjs\./

/** An import of a module, with what it imports. */
const IMPORT = /import\s+(?:type\s+)?(?:[\w*\s{},]*?\s+from\s+)?["']([^"']+)["']/g

function sourceFiles(): string[] {
  return readdirSync(SOURCE, { recursive: true, encoding: "utf8" })
    .filter((file) => /\.([jt]sx?|css)$/.test(file) && !/\.(test|spec)\./.test(file))
    .map((file) => file.split(path.sep).join("/"))
}

function read(file: string): string {
  return readFileSync(path.join(SOURCE, file), "utf8")
}

function token(name: string): string | undefined {
  return new RegExp(`--font-${name}:\\s*([^;]+);`).exec(STYLES)?.[1]?.trim()
}

describe("faces", () => {
  it("are Inter for text, Outfit for headings and Monocraft for code and figures", () => {
    expect(token("sans")).toBe('"Inter Variable", sans-serif')
    expect(token("heading")).toBe('"Outfit Variable", sans-serif')
    expect(token("mono")).toBe('"Monocraft", ui-monospace, monospace')
    expect(STYLES).toContain('@import "@fontsource-variable/inter";')
    expect(STYLES).toContain('@import "@fontsource-variable/outfit";')
    expect(STYLES).toMatch(/@font-face\s*\{[^}]*font-family:\s*"Monocraft";[^}]*url\("\/fonts\/Monocraft\.ttf"\)/)
  })
})

describe("faces and icons", () => {
  it("come only from their own packages", () => {
    const packages = Object.keys({ ...PACKAGE.dependencies, ...PACKAGE.devDependencies })
    assert.deepEqual(
      packages.filter(
        (name) => FACE_OR_ICON.test(name) && !FACE_AND_ICON_PACKAGES.includes(name) && name !== "@iconify/types",
      ),
      [],
      "Steward is set in Inter, Outfit and Monocraft, and draws Pixelarticons. Another face or icon set does not belong.",
    )
    for (const name of FACE_AND_ICON_PACKAGES) expect(packages).toContain(name)
  })

  it("are bundled, never fetched while Steward runs", () => {
    const files = [
      ...sourceFiles().map((file) => [file, read(file)] as const),
      ["../index.html", read("../index.html")] as const,
    ]
    expect(files.filter(([, text]) => RUNTIME_FETCH.test(text)).map(([file]) => file)).toEqual([])
  })
})

describe("icons", () => {
  it("are drawn only through the icons module", () => {
    const offenders = sourceFiles()
      .filter((file) => file !== ICONS)
      .flatMap((file) =>
        [...read(file).matchAll(IMPORT)]
          .map((match) => match[1] ?? "")
          .filter(
            (module) => !/^(?:@\/|\.)/.test(module) && FACE_OR_ICON.test(module) && !module.startsWith("@fontsource"),
          )
          .map((module) => `${file}  ${module}`),
      )
    assert.deepEqual(
      offenders,
      [],
      `These files import an icon set. Import the icon from "@/components/ui/icons", or add it there.`,
    )
  })

  it("are each one Pixelarticons icon, none drawn for two names", () => {
    const names = Object.values(PIXELARTICONS)
    expect(names.filter((name) => pixelarticons.icons[name] === undefined)).toEqual([])
    expect(names.filter((name, index) => names.indexOf(name) !== index)).toEqual([])
    expect(Object.keys(PIXELARTICON_PATHS).toSorted()).toEqual([...new Set(names)].toSorted())
    const unlike = names.filter((name) => {
      const body = pixelarticons.icons[name]?.body ?? ""
      const paths = [...body.matchAll(/\sd="([^"]+)"/g)].map((match) => match[1])
      const drawn = PIXELARTICON_PATHS[name]
      return /<(?!\/?(?:path|g)\b)/.test(body) || drawn.length === 0 || drawn.join("|") !== paths.join("|")
    })
    expect(unlike).toEqual([])
  })

  it("are never drawn by hand", () => {
    assert.deepEqual(
      sourceFiles().filter((file) => !OWN_SVG.has(file) && /<svg\b/.test(read(file))),
      [],
      `These files draw an <svg> of their own. Draw an icon from "@/components/ui/icons".`,
    )
  })

  /** A broken pattern would make the rule above pass by finding nothing. */
  it("recognise an import of another icon set", () => {
    const imports = [...'import { X } from "@phosphor-icons/react"\nimport { Y } from "lucide-react"'.matchAll(IMPORT)]
    expect(imports.map((match) => FACE_OR_ICON.test(match[1] ?? ""))).toEqual([true, true])
    expect(sourceFiles()).toContain(ICONS)
  })
})
