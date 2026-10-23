/**
 * Drives smp's settings tab in Chromium over a running Steward preview and checks the page and the volume.
 *
 * `SettingsPageEndToEndTest` starts the preview and runs this with Node:
 * `node settings-plugins.ts <base> <state> <volumes> <shots> <chromium>`.
 */
import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { mkdirSync, readFileSync, readdirSync, readlinkSync } from "node:fs"
import path from "node:path"
import { chromium, type Page } from "playwright-core"

const [base, state, volumes, shots, executablePath] = process.argv.slice(2)
const NOTES = "smp/plugins/bStats/notes.txt"
const NOTES_TEXT = "Opened as text: no form for a .txt file."
const ICONS = {
  CoreProtect: "https://cdn.modrinth.com/data/Lu3KuzdV/b2c4b7b0033ab09cc166f2848003ef3a02c70a83.png",
  LuckPerms: "https://cdn.modrinth.com/data/Vebnzrzj/90943902cc650e95b167265b8f2d7c893f82c8f4_96.webp",
}

/** Returns every file under the volumes by path, a link by its target, so any write or new file shows. */
function snapshot(): Map<string, string> {
  const files = new Map<string, string>()
  for (const entry of readdirSync(volumes, { recursive: true, withFileTypes: true })) {
    const full = path.join(entry.parentPath, entry.name)
    const at = path.relative(volumes, full)
    if (entry.isSymbolicLink()) files.set(at, `link:${readlinkSync(full)}`)
    else if (entry.isFile()) {
      const bytes = readUnlessGone(full)
      if (bytes) files.set(at, createHash("sha256").update(bytes).digest("hex"))
    }
  }
  return files
}

/** Reads a file, or nothing when a save's temporary file was renamed away since the listing. */
function readUnlessGone(full: string): Buffer | undefined {
  try {
    return readFileSync(full)
  } catch (error) {
    if (error instanceof Error && "code" in error && error.code === "ENOENT") return undefined
    throw error
  }
}

function changed(before: Map<string, string>, after: Map<string, string>): string[] {
  const paths = new Set([...before.keys(), ...after.keys()])
  return [...paths].filter((at) => before.get(at) !== after.get(at)).toSorted()
}

async function until(what: string, check: () => boolean | Promise<boolean>) {
  const end = Date.now() + 15_000
  while (!(await check())) {
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`)
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
}

/** Returns the rows of the file list: the Nordtal groups before the "Plugin files" heading, the plugins after it. */
async function rows(page: Page): Promise<{ nordtal: string[]; plugins: string[] }> {
  return page.getByRole("navigation", { name: "Files" }).evaluate((nav) => {
    const rule = [...nav.querySelectorAll("h2")].find((heading) => heading.textContent === "Plugin files") ?? null
    const texts = (before: boolean) =>
      [...nav.querySelectorAll("button")]
        .filter(
          (button) =>
            rule !== null && (button.compareDocumentPosition(rule) & Node.DOCUMENT_POSITION_FOLLOWING) > 0 === before,
        )
        .map((button) => button.textContent?.trim() ?? "")
    return { nordtal: texts(true), plugins: texts(false) }
  })
}

/** Returns what leads a row: an image's address, else the markup of its icon. */
async function icon(page: Page, row: string): Promise<string> {
  return page
    .getByRole("navigation", { name: "Files" })
    .getByRole("button", { name: row, exact: true })
    .evaluate((button) => {
      const lead = button.firstElementChild
      return lead instanceof HTMLImageElement ? lead.src : (lead?.innerHTML ?? "")
    })
}

async function open(page: Page) {
  await page.goto(`${base}/services/smp?tab=settings`)
  // Nordtal's groups and the plugin files arrive in two requests, in either order.
  await page.getByRole("button", { name: "Distances", exact: true }).waitFor()
  await page.getByRole("button", { name: "CoreProtect", exact: true }).waitFor()
}

async function expand(page: Page, ...names: (string | RegExp)[]) {
  for (const name of names) await page.getByRole("button", { name, exact: typeof name === "string" }).click()
}

const browser = await chromium.launch({ executablePath })
try {
  mkdirSync(shots, { recursive: true })
  const desktop = await browser.newPage({ storageState: state, viewport: { width: 1280, height: 900 } })
  await open(desktop)

  assert.deepEqual(await rows(desktop), {
    nordtal: ["Distances"],
    plugins: ["B stats", "Chunky", "CoreProtect", "LuckPerms"],
  })
  assert.equal(await icon(desktop, "CoreProtect"), ICONS.CoreProtect)
  assert.equal(await icon(desktop, "LuckPerms"), ICONS.LuckPerms)
  const plug = await desktop
    .getByRole("tab", { name: "Plugins" })
    .evaluate((tab) => tab.querySelector("svg")?.innerHTML)
  assert.equal(await icon(desktop, "Chunky"), plug, "a plugin without an icon shows the plugins icon")

  await expand(desktop, "B stats", "LuckPerms", /yaml-storage/)
  assert.deepEqual(await rows(desktop), {
    nordtal: ["Distances"],
    plugins: [
      "B stats",
      "config.yml",
      "notes.txt",
      "Chunky",
      "CoreProtect",
      "LuckPerms",
      "config.yml",
      "contexts.json",
      "yaml-storagegroups",
      "admin.yml",
      "default.yml",
    ],
  })
  const folder = await desktop
    .getByRole("button", { name: /yaml-storage/ })
    .evaluate((row) => row.firstElementChild?.innerHTML)
  assert.equal(await icon(desktop, "B stats"), folder, "a folder no jar claims shows the folder icon")
  assert.notEqual(folder, plug)

  const before = snapshot()
  await desktop.getByRole("button", { name: "notes.txt", exact: true }).click()
  const text = desktop.getByRole("textbox", { name: "B stats notes.txt" })
  await text.click()
  await desktop.keyboard.press("Control+Home")
  await desktop.keyboard.press("End")
  await desktop.keyboard.type(" Discarded.")
  await desktop.getByRole("button", { name: "Save 1" }).waitFor()
  await desktop.getByRole("button", { name: "Discard" }).click()
  await until("the discard", async () => (await text.textContent()) === NOTES_TEXT)
  assert.deepEqual(changed(before, snapshot()), [], "a discard writes nothing")

  await text.click()
  await desktop.keyboard.press("Control+Home")
  await desktop.keyboard.press("End")
  await desktop.keyboard.type(" Saved.")
  await desktop.getByRole("button", { name: "Save 1" }).click()
  await until("the save", () => changed(before, snapshot()).length > 0)
  await desktop.getByText("B stats notes.txt saved.").waitFor()
  assert.deepEqual(changed(before, snapshot()), [NOTES], "a save writes its own file only")
  assert.equal(readFileSync(path.join(volumes, NOTES), "utf8"), `${NOTES_TEXT} Saved.\n`)
  await desktop.screenshot({ path: path.join(shots, "settings-1280.png"), fullPage: true, animations: "disabled" })

  const phone = await browser.newPage({ storageState: state, viewport: { width: 390, height: 844 } })
  await open(phone)
  await expand(phone, "LuckPerms")
  await phone.screenshot({ path: path.join(shots, "settings-390.png"), fullPage: true, animations: "disabled" })
  await phone.getByRole("button", { name: "contexts.json", exact: true }).click()
  await phone.getByRole("button", { name: "Back to the files" }).waitFor()
  await phone.waitForLoadState("networkidle")
  await phone.screenshot({ path: path.join(shots, "settings-file-390.png"), fullPage: true, animations: "disabled" })
} finally {
  await browser.close()
}
