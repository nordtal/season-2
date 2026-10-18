import { configure } from "@testing-library/react"

/** The one-second default fails on a busy host and not on the code. */
configure({ asyncUtilTimeout: 5_000 })

/** jsdom always answers no, because its 1024px window is a desktop. */
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

/** cmdk needs one, and there is no layout. */
if (typeof globalThis.ResizeObserver === "undefined") {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

/** cmdk calls it as its selection moves. */
if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => undefined
}
