import { configure } from "@testing-library/react"

/**
 * Five seconds for async queries, because the one-second default fails on a busy host and not on the code.
 */
configure({ asyncUtilTimeout: 5_000 })

/**
 * `window.matchMedia` for jsdom, which always answers no, because jsdom's 1024px window is a desktop.
 */
if (typeof window !== "undefined" && !window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList
}

/**
 * A `ResizeObserver` for jsdom that observes nothing, because cmdk needs one and there is no layout.
 */
if (typeof globalThis.ResizeObserver === "undefined") {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

/** `scrollIntoView` for jsdom, which cmdk calls as its selection moves. */
if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => undefined
}
