import { fireEvent, screen } from "@testing-library/react"

/** Narrows a Testing Library element to a subclass with `instanceof` and no cast. */

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

export function asElement(element: Element | null): HTMLElement {
  return narrow(element, HTMLElement, "HTMLElement")
}

/**
 * Returns `dataset` of an element that may be SVG, because the network diagram's `<path>` and `<g>` carry `data-*` too.
 */
export function datasetOf(element: Element | null): DOMStringMap {
  if (!(element instanceof HTMLElement) && !(element instanceof SVGElement)) {
    throw new Error(`expected an element, got ${element?.tagName ?? "nothing"}`)
  }
  return element.dataset
}

/**
 * The text of Steward's tooltip on an element, opened the way the keyboard opens it and closed again, or null without
 * one. A disabled control's tip sits on the focusable span around it.
 */
export function tipOf(element: Element | null): string | null {
  const target = asElement(element)
  const trigger = asElement(target.matches(":disabled") ? target.parentElement : target)
  fireEvent.focus(trigger)
  const tip = screen.queryByRole("tooltip")?.textContent ?? null
  fireEvent.blur(trigger)
  return tip
}
