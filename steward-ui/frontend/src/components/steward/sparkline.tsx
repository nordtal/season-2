import { useMemo } from "react"
import { Area, AreaChart, ResponsiveContainer } from "recharts"

import type { MetricPoint } from "@/lib/api"
import { Skeleton } from "@/components/ui/skeleton"

/**
 * A curve with no axis, no grid and no tooltip - the shape of a value over time and nothing else.
 *
 * `SeriesChart` is the full chart, and it is still what a page with 96 px to spend on one metric
 * should reach for. This is what steward/64 needed instead: the start page's metric row has room
 * for a number and a one-line hint, not for tick labels, so the CPU history that used to sit under
 * the Host card shrank to this rather than moving wholesale - a sparkline answers "is this climbing
 * or flat", which is all a tile this small can ask of it. `SeriesChart` still answers "at what time
 * exactly", on Operations, where there is space for the question.
 *
 * `aria-hidden`: the number above the line already carries the reading, in text a screen reader can
 * announce; the curve repeats it for an eye glancing at the tile and adds nothing a reader could
 * use a second sentence for.
 */
export function Sparkline({
  points,
  colour = "var(--chart-4)",
  height = 28,
}: {
  /** Absent while the series is still being read - drawn as a bar of the same height. */
  points?: MetricPoint[]
  colour?: string
  height?: number
}) {
  const data = useMemo(
    () => (points ?? []).map((point) => ({ at: new Date(point.at).getTime(), value: point.value })),
    [points],
  )

  // steward/120: no skeleton chart is wanted, only a skeleton of about the size the chart will
  // have, filling the same area until it is drawn. So no faked axis and no faked curve - the strip
  // this occupies, shimmering, and nothing else.
  if (points === undefined) {
    return <Skeleton style={{ height }} className="w-full" />
  }

  // A read that came back with nothing in it is a different statement, and it is not an error worth
  // a sentence here - `SeriesChart` already says so where there is room for it. A flat, empty strip
  // is the quiet version of the same fact.
  if (data.length === 0) {
    return <div style={{ height }} aria-hidden />
  }

  return (
    <div style={{ height }} aria-hidden>
      {/*
        `initialDimension`: jsdom does no layout, so `getBoundingClientRect` on the wrapping div
        answers zero by zero and recharts refuses to draw into that - `ChartContainer` next to this
        file carries the identical prop for the identical reason. A real browser measures the tile
        and ignores this after the first frame; jsdom never gets a first real measurement at all.
      */}
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
