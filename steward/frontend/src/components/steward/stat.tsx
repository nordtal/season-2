import type { ReactNode } from "react"
import { cn } from "cn"

import { Skeleton, SkeletonText } from "@/components/ui/skeleton"

/**
 * One labelled number with a caveat as a hint underneath.
 *
 * Without `value` it shows the label and a bar where the number will be, so nothing moves when the number arrives.
 */
export function Stat({
  label,
  value,
  hint,
  tone,
  className,
  valueClassName,
}: {
  label: string
  /** Absent while the figure loads. */
  value?: ReactNode
  hint?: ReactNode
  tone?: "ok" | "warn" | "down"
  className?: string
  valueClassName?: string
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
          valueClassName,
        )}
      >
        {value === undefined ? <SkeletonText width="short" className="h-[1lh]" /> : value}
      </span>
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </div>
  )
}

/**
 * A bar for something with a ceiling, such as disk or memory. It changes colour at a threshold.
 */
export function UsageBar({
  used,
  total,
  warnAt = 80,
  dangerAt = 90,
}: {
  /** Both are absent while the reading loads. The track is then drawn empty and shimmering. */
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
