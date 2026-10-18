import type { CSSProperties } from "react"

export { cn } from "cn"

export type CSSVars = CSSProperties & Record<`--${string}`, string | number>
