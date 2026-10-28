import type { CSSProperties } from "react"
import { createCn } from "cn/config"

/**
 * Joins class names and lets a later Tailwind class win over an earlier one that sets the same thing. It knows the
 * tokens that `index.css` adds to a namespace, so `text-caption` counts as a size and not as a colour.
 */
export const cn = createCn({
  extend: { theme: { text: ["caption", "badge", "inline"], container: ["page"], spacing: ["list"] } },
})

export type CSSVars = CSSProperties & Record<`--${string}`, string | number>
