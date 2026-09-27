import { useMemo } from "react"
import { Area, AreaChart, ResponsiveContainer } from "recharts"

import type { MetricPoint } from "@/lib/api"
import { Skeleton } from "@/components/ui/skeleton"

/** A bare curve for a start page tile, `aria-hidden` since the number above it already carries the reading. */
export function Sparkline({
  points,
  colour = "var(--chart-4)",
  height = 28,
}: {
  /** Absent while the series is still being read, drawn as a bar of the same height. */
  points?: MetricPoint[]
  colour?: string
  height?: number
}) {
  const data = useMemo(
    () => (points ?? []).map((point) => ({ at: new Date(point.at).getTime(), value: point.value })),
    [points],
  )

  /** No faked chart, only the strip it will fill, shimmering. */
  if (points === undefined) {
    return <Skeleton style={{ height }} className="w-full" />
  }

  /** An empty read is not an error here; `SeriesChart` says so where there is room. */
  if (data.length === 0) {
    return <div style={{ height }} aria-hidden />
  }

  return (
    <div style={{ height }} aria-hidden>
      {/* `initialDimension`, since jsdom measures zero by zero and recharts will not draw into that. */}
      <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 200, height }}>
        <AreaChart data={data} margin={{ top: 2, right: 0, bottom: 0, left: 0 }}>
          <Area
            dataKey="value"
            type="monotone"
            stroke={colour}
            strokeWidth={1.5}
            fill={colour}
            fillOpacity={0.15}
            isAnimationActive={false}
            dot={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
