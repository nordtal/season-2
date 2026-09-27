/**
 * Narrows a Testing Library element for properties only its subclass has, such as `disabled`, `value` or `style`.
 *
 * An `instanceof` check, not a cast; it never throws in a passing test.
 */

function narrow<T extends Element>(element: Element | null, ctor: new (...args: never[]) => T, label: string): T {
  if (!(element instanceof ctor)) {
    throw new Error(`expected a ${label}, got ${element?.tagName ?? "nothing"}`)
  }
  return element
}

export function asButton(element: Element | null): HTMLButtonElement {
  return narrow(element, HTMLButtonElement, "button")
}

export function asInput(element: Element | null): HTMLInputElement {
  return narrow(element, HTMLInputElement, "input")
}

export function asTextArea(element: Element | null): HTMLTextAreaElement {
  return narrow(element, HTMLTextAreaElement, "textarea")
}

export function asImage(element: Element | null): HTMLImageElement {
  return narrow(element, HTMLImageElement, "img")
}

export function asAnchor(element: Element | null): HTMLAnchorElement {
  return narrow(element, HTMLAnchorElement, "a")
}

/** The same narrowing, for a query that answers a plain `Element` rather than one of its subclasses. */
export function asElement(element: Element | null): HTMLElement {
  return narrow(element, HTMLElement, "HTMLElement")
}

/** `dataset` off an element that may be SVG, since the network diagram's `<path>` and `<g>` carry `data-*` too. */
export function datasetOf(element: Element | null): DOMStringMap {
  if (!(element instanceof HTMLElement) && !(element instanceof SVGElement)) {
    throw new Error(`expected an element, got ${element?.tagName ?? "nothing"}`)
  }
  return element.dataset
}
