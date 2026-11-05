import type { CSSProperties } from "react"
import { createCn } from "cn/config"

/**
 * Joins class names and lets a later Tailwind class win over an earlier one that sets the same thing. It knows the
 * tokens that `index.css` adds to a namespace, so `text-caption` counts as a size and not as a colour.
 */
export const cn = createCn({
  extend: { theme: { text: ["caption", "badge", "inline"], container: ["page"], spacing: ["list"] } },
})

/** Whether a text inside the element is cut off by its ellipsis. */
export function cutOff(element: Element): boolean {
  return [...element.querySelectorAll(".truncate")].some((text) => text.scrollWidth > text.clientWidth)
}

export type CSSVars = CSSProperties & Record<`--${string}`, string | number>
