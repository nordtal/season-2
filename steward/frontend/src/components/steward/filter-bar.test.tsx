import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { useState } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { FilterBar, FilterToggle, SearchField } from "@/components/steward/filter-bar"

afterEach(cleanup)

function Typed({ onSubmit }: { onSubmit?: (value: string) => void }) {
  const [value, setValue] = useState("")
  return (
    <FilterBar>
      <SearchField value={value} onValueChange={setValue} onSubmit={onSubmit} label="Search the roster" />
    </FilterBar>
  )
}

describe("SearchField", () => {
  it("draws the magnifier inside the field's group and a clear button only once something is typed", () => {
    render(<Typed />)
    const field = screen.getByRole<HTMLInputElement>("searchbox", { name: "Search the roster" })
    const group = field.closest("[data-slot=input-group]")
    expect(group?.querySelector("svg")).not.toBeNull()
    expect(screen.queryByRole("button", { name: "Clear the search" })).toBeNull()

    fireEvent.change(field, { target: { value: "ally" } })
    fireEvent.click(screen.getByRole("button", { name: "Clear the search" }))
    expect(field.value).toBe("")
    expect(screen.queryByRole("button", { name: "Clear the search" })).toBeNull()
  })

  it("with a submit, filters on Enter alone and clears the filter with the field", () => {
    const submit = vi.fn<(value: string) => void>()
    render(<Typed onSubmit={submit} />)
    const field = screen.getByRole("searchbox", { name: "Search the roster" })

    fireEvent.change(field, { target: { value: " 214906139328839681 " } })
    expect(submit).not.toHaveBeenCalled()
    fireEvent.submit(field)
    expect(submit).toHaveBeenLastCalledWith("214906139328839681")

    fireEvent.click(screen.getByRole("button", { name: "Clear the search" }))
    expect(submit).toHaveBeenLastCalledWith("")
  })
})

describe("FilterToggle", () => {
  it("is a button that says whether it is pressed, and ticks itself while it is", () => {
    const change = vi.fn<(pressed: boolean) => void>()
    const { rerender } = render(
      <FilterToggle pressed={false} onPressedChange={change}>
        With access only
      </FilterToggle>,
    )
    const toggle = screen.getByRole("button", { name: "With access only" })
    expect(toggle.getAttribute("aria-pressed")).toBe("false")
    expect(toggle.querySelector("svg")).toBeNull()
    fireEvent.click(toggle)
    expect(change).toHaveBeenCalledWith(true)

    rerender(
      <FilterToggle pressed onPressedChange={change}>
        With access only
      </FilterToggle>,
    )
    expect(toggle.getAttribute("aria-pressed")).toBe("true")
    expect(toggle.querySelector("svg")).not.toBeNull()
  })
})

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..")

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(full)
    return entry.name.endsWith(".tsx") && !entry.name.includes(".test.") ? [full] : []
  })
}

/** The places that draw a magnifier of their own, each for a reason the filter bar does not cover. */
const OWN_MAGNIFIER = new Map<string, string>([
  [path.join("components", "ui", "command.tsx"), "the Ctrl+K field, which cmdk draws"],
  [path.join("app", "island.tsx"), "the dock's button that opens Ctrl+K, not a field"],
  [path.join("components", "steward", "filter-bar.tsx"), "the filter bar itself"],
])

describe("every search field is the filter bar's", () => {
  it("leaves no page drawing a magnifier or a search field of its own", () => {
    const found: string[] = []
    for (const file of sourceFiles(source)) {
      const relative = path.relative(source, file)
      if (OWN_MAGNIFIER.has(relative)) continue
      const text = readFileSync(file, "utf8")
      if (/\bMagnifyingGlassIcon\b|type="search"|role="searchbox"/.test(text)) found.push(relative)
    }
    expect(found).toEqual([])
  })
})
