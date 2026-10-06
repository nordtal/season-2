import { act, render, renderHook } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

/** Imported afresh in each test, since the switch is read from storage once, when the module loads. */
async function load() {
  vi.resetModules()
  return import("@/app/viewport-diagnostics")
}

describe("the viewport diagnostics readout", () => {
  afterEach(() => {
    window.localStorage.clear()
  })

  it("draws nothing until it is switched on", async () => {
    const { ViewportDiagnostics } = await load()
    const { container } = render(<ViewportDiagnostics />)
    expect(container.querySelector("[data-viewport-diagnostics]")).toBeNull()
  })

  it("shows the numbers once switched on, and keeps the switch in storage", async () => {
    const { ViewportDiagnostics, useViewportDiagnostics } = await load()
    const { container } = render(<ViewportDiagnostics />)
    const { result } = renderHook(() => useViewportDiagnostics())

    act(() => result.current[1](true))

    const readout = container.querySelector("[data-viewport-diagnostics]")
    expect(readout?.textContent).toContain(`inner ${window.innerWidth}×${window.innerHeight}`)
    expect(readout?.textContent).toContain("--app-height")
    expect(window.localStorage.getItem("steward:viewport-diagnostics")).toBe("on")

    act(() => result.current[1](false))
    expect(container.querySelector("[data-viewport-diagnostics]")).toBeNull()
    expect(window.localStorage.getItem("steward:viewport-diagnostics")).toBeNull()
  })

  it("is already on after a restart of the app, so it is up when the strip appears", async () => {
    window.localStorage.setItem("steward:viewport-diagnostics", "on")
    const { ViewportDiagnostics } = await load()
    const { container } = render(<ViewportDiagnostics />)
    expect(container.querySelector("[data-viewport-diagnostics]")).not.toBeNull()
  })
})
