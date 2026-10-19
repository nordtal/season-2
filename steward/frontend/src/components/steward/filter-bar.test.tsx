import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { useState } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { FilterBar, FilterChoice, FilterMenu, FilterOption, SearchField } from "@/components/steward/filter-bar"

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
    expect(field.className).toContain("[&::-webkit-search-cancel-button]:hidden")

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

describe("FilterOption", () => {
  it("says whether it is pressed, and ticks itself after the label, which never moves", () => {
    const change = vi.fn<(pressed: boolean) => void>()
    const { rerender } = render(
      <FilterOption pressed={false} onPressedChange={change}>
        With access only
      </FilterOption>,
    )
    const option = screen.getByRole("button", { name: "With access only" })
    expect(option.getAttribute("aria-pressed")).toBe("false")
    expect(option.firstChild?.textContent).toBe("With access only")
    expect(option.lastElementChild?.tagName).toBe("svg")
    expect(option.lastElementChild?.getAttribute("class")).toContain("invisible")
    fireEvent.click(option)
    expect(change).toHaveBeenCalledWith(true)

    rerender(
      <FilterOption pressed onPressedChange={change}>
        With access only
      </FilterOption>,
    )
    expect(option.getAttribute("aria-pressed")).toBe("true")
    expect(option.firstChild?.textContent).toBe("With access only")
    expect(option.lastElementChild?.getAttribute("class")).not.toContain("invisible")
  })
})

describe("FilterChoice", () => {
  it("picks one option at most, and pressing the chosen one again clears it", () => {
    const change = vi.fn<(value: string) => void>()
    const options = [
      { value: "OPEN", label: "Open" },
      { value: "PAID", label: "Paid" },
    ]
    render(<FilterChoice label="Status" value="PAID" onValueChange={change} options={options} />)
    expect(screen.getByRole("group", { name: "Status" })).not.toBeNull()
    fireEvent.click(screen.getByRole("button", { name: "Open" }))
    expect(change).toHaveBeenLastCalledWith("OPEN")
    fireEvent.click(screen.getByRole("button", { name: "Paid" }))
    expect(change).toHaveBeenLastCalledWith("")
  })
})

describe("FilterMenu", () => {
  it("counts the active filters on its button and opens them in a popover", () => {
    const { rerender } = render(
      <FilterMenu active={0}>
        <FilterOption pressed={false} onPressedChange={() => {}}>
          Admins
        </FilterOption>
      </FilterMenu>,
    )
    expect(screen.getByRole("button", { name: "Filters" })).not.toBeNull()
    rerender(
      <FilterMenu active={2}>
        <FilterOption pressed onPressedChange={() => {}}>
          Admins
        </FilterOption>
      </FilterMenu>,
    )
    const trigger = screen.getByRole("button", { name: "Filters 2" })
    expect(screen.queryByRole("button", { name: "Admins" })).toBeNull()
    fireEvent.click(trigger)
    expect(screen.getByRole("button", { name: "Admins" })).not.toBeNull()
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

/**
 * The places that draw their own magnifier. Each has a reason that the filter bar does not cover.
 */
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

describe("every search field looks the same", () => {
  it("leaves no page styling the search field of its own", () => {
    const styled: string[] = []
    for (const file of sourceFiles(source)) {
      const text = readFileSync(file, "utf8")
      for (const tag of text.matchAll(/<SearchField\b[\s\S]*?\/>/g)) {
        if (/\b(className|inputClassName|style)=/.test(tag[0])) styled.push(path.relative(source, file))
      }
    }
    expect(styled).toEqual([])
  })
})
