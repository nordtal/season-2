import type { ReactNode } from "react"
import { cn } from "cn"

import { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/**
 * One labelled number.
 *
 * The label is small and quiet, the figure is large and `tabular-nums`, and the hint underneath is
 * where the caveat goes - "share of the host, no container has a limit" belongs next to the number
 * it qualifies and nowhere else.
 *
 * **`value` is optional, and that is the waiting shape** (steward/120). The label is known before
 * the figure is - it is written into the page, not fetched - so a stat that is still waiting shows
 * its label and a bar where the number will be. Nothing moves when the number lands, and the page
 * already reads as itself while it is empty.
 */
export function Stat({
  label,
  value,
  hint,
  tone,
  className,
}: {
  label: string
  /** Absent while the figure is on its way. */
  value?: ReactNode
  hint?: ReactNode
  tone?: "ok" | "warn" | "down"
  className?: string
}) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-0.5", className)}>
      <span className="text-xs font-medium font-heading text-muted-foreground">{label}</span>
      <span
        className={cn(
          "truncate text-xl font-semibold tnum",
          tone === "ok" && "text-success",
          tone === "warn" && "text-warning",
          tone === "down" && "text-destructive",
        )}
      >
        {value === undefined ? <SkeletonText width="short" className="h-[1lh]" /> : value}
      </span>
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </div>
  )
}

/**
 * A bar for something that has a ceiling - disk, memory.
 *
 * shadcn's `Progress` is the official component and is what this uses; what it does not have is the
 * colour change at a threshold, which is the entire reason an operator glances at a bar at all.
 */
export function UsageBar({
  used,
  total,
  warnAt = 80,
  dangerAt = 90,
}: {
  /** Both absent while the reading is on its way: the track is then drawn empty and shimmering. */
  used?: number
  total?: number
  warnAt?: number
  dangerAt?: number
}) {
  if (used === undefined || total === undefined) {
    return <Skeleton className="h-1.5 w-full rounded-full" />
  }
  const share = total > 0 ? Math.min(100, (used / total) * 100) : 0
  const tone = share >= dangerAt ? "bg-destructive" : share >= warnAt ? "bg-warning" : "bg-success"
  return (
    <div
      className="h-1.5 w-full overflow-hidden rounded-full bg-secondary"
      role="meter"
      aria-valuenow={Math.round(share)}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div
        className={cn("h-full rounded-full transition-[width] duration-500 ease-out", tone)}
        style={{ width: `${share}%` }}
      />
    </div>
  )
}
