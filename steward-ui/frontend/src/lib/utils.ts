import type { CSSProperties } from "react"

export { cn } from "cn"

/** A style object that also sets CSS custom properties, which `CSSProperties` has no field for. */
export type CSSVars = CSSProperties & Record<`--${string}`, string | number>
