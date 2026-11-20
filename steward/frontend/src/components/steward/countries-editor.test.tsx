import { useState } from "react"
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react"
import { afterEach, expect, it } from "vitest"

import type { ConfigEntry, GlyphInfo } from "@/lib/api"
import { Countries } from "@/components/steward/countries-editor"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { TooltipProvider } from "@/components/ui/tooltip"

const fields = ["key", "name", "zone", "language", "emoji", "glyph"]
const labels = ["Key", "Name", "Time zone", "Language", "Discord emoji", "Flag glyph"]
const entry = (key: string, index: number): ConfigEntry => ({
  key,
  path: key,
  label: labels[index],
  explanation: "",
  noExplanationNeeded: true,
  filled: true,
  kind: "SCALAR",
  type: "STRING",
  editable: true,
  secret: false,
  environmentOverridden: false,
  value: "",
})
const list: ConfigEntry = {
  ...entry("countries", 0),
  label: "Countries",
  kind: "SECTIONS",
  template: fields.map(entry),
  rules: [{ rule: "MOST_ENTRIES", limit: 25 }],
}
const country = {
  key: "de",
  name: "Deutschland",
  zone: "Europe/Berlin",
  language: "de",
  emoji: "DE",
  glyph: "flag-germany",
}
const glyphs: GlyphInfo[] = [
  { name: "flag-germany", codePoint: 1, height: 7, ascent: 7, image: "germany.png" },
  { name: "flag-austria", codePoint: 2, height: 7, ascent: 7, image: "austria.png" },
]
function Harness({ initial = [country] }: { initial?: SectionValues[] }) {
  const [countries, set] = useState<SectionValues[]>(initial)
  return (
    <TooltipProvider>
      <Countries
        list={list}
        countries={countries}
        disabled={false}
        glyphs={glyphs}
        languages={["en", "de"]}
        onChange={set}
      />
    </TooltipProvider>
  )
}
afterEach(cleanup)

it("shows the Discord emoji and released pack flag beside the country", () => {
  const { container } = render(<Harness />)
  expect(screen.getByRole("button", { name: /Deutschland/ }).textContent).toContain("DE")
  expect(container.querySelector("img")?.getAttribute("src")).toBe("/glyphs/germany.png")
})
it("offers only network languages and updates the country without choosing a time zone for the member", async () => {
  render(<Harness />)
  fireEvent.click(screen.getByRole("button", { name: /Deutschland/ }))
  fireEvent.click(screen.getByRole("combobox", { name: "Language" }))
  const options = within(await screen.findByRole("listbox"))
  expect(options.getAllByRole("option").map((option) => option.textContent)).toEqual(["en", "de"])
  fireEvent.click(options.getByRole("option", { name: "en" }))
  expect(screen.getByRole("textbox", { name: "Time zone" }).getAttribute("value")).toBe("Europe/Berlin")
  fireEvent.click(screen.getByRole("button", { name: "Done" }))
  expect(screen.getByRole("button", { name: /Deutschland/ }).textContent).toContain("en")
})
it("chooses a flag from the release and updates its preview", async () => {
  const { container } = render(<Harness />)
  fireEvent.click(screen.getByRole("button", { name: /Deutschland/ }))
  fireEvent.click(screen.getByRole("combobox", { name: "Flag glyph" }))
  fireEvent.click(within(await screen.findByRole("listbox")).getByRole("option", { name: "flag-austria" }))
  fireEvent.click(screen.getByRole("button", { name: "Done" }))
  expect(container.querySelector("img")?.getAttribute("src")).toBe("/glyphs/austria.png")
})
it("caps the list at Discords twenty five choices", () => {
  render(<Harness initial={Array.from({ length: 25 }, (_, index) => ({ ...country, key: `country-${index}` }))} />)
  expect(screen.getByRole("button", { name: "Add region" }).hasAttribute("disabled")).toBe(true)
})

it("opens a new country and keeps its unfinished row named", () => {
  render(<Harness />)
  fireEvent.click(screen.getByRole("button", { name: "Add region" }))
  expect(screen.getByRole("dialog", { name: "Countries" })).toBeTruthy()
  expect(screen.getByRole("textbox", { name: "Key" }).getAttribute("value")).toBe("")
  fireEvent.click(screen.getByRole("button", { name: "Done" }))
  expect(screen.getByRole("button", { name: "Countries" })).toBeTruthy()
})
