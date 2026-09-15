import { useMemo } from "react"
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts"

import type { MetricPoint } from "@/lib/api"
import { LOCALE } from "@/lib/format"
import { ChartContainer, ChartTooltip, ChartTooltipContent } from "@/components/ui/chart"
import type { ChartConfig } from "@/components/ui/chart"

/**
 * One curve, out of Postgres.
 *
 * §10c decides where the points come from and it is worth repeating here, because the obvious
 * shortcut is wrong: Docker's `/stats` answers **now** and keeps no history, so a chart drawn from
 * the daemon draws one point and calls it a line. `steward-worker` samples every 30 seconds into
 * `metric_sample`; this reads that table through `/api/metrics`.
 *
 * The colour is a chart colour and never the brand. Blue in this interface means "you can click
 * this", and a blue area chart is the one blue thing on the page that does nothing.
 */
export function SeriesChart({
  points,
  label,
  format,
  colour = "var(--chart-4)",
  height = 120,
}: {
  points: MetricPoint[]
  label: string
  format: (value: number) => string
  colour?: string
  height?: number
}) {
  const data = useMemo(
    () => points.map((point) => ({ at: new Date(point.at).getTime(), value: point.value })),
    [points],
  )

  const config = useMemo<ChartConfig>(() => ({ value: { label, color: colour } }), [label, colour])

  if (data.length === 0) {
    return (
      <div
        style={{ height }}
        className="flex items-center justify-center rounded-md border border-dashed border-border text-xs text-muted-foreground"
      >
        No data points yet - steward-worker writes one every 30 seconds.
      </div>
    )
  }

  return (
    <ChartContainer config={config} className="w-full" style={{ height }}>
      <AreaChart data={data} margin={{ left: 4, right: 4, top: 4, bottom: 0 }}>
        <defs>
          <linearGradient id={`fill-${label}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={colour} stopOpacity={0.35} />
            <stop offset="100%" stopColor={colour} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} strokeDasharray="3 3" stroke="var(--border)" />
        <XAxis
          dataKey="at"
          type="number"
          scale="time"
          domain={["dataMin", "dataMax"]}
          tickLine={false}
          axisLine={false}
          minTickGap={48}
          tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
          tickFormatter={(value: number) =>
            new Intl.DateTimeFormat(LOCALE, { hour: "2-digit", minute: "2-digit" }).format(value)
          }
        />
        <YAxis
          width={52}
          tickLine={false}
          axisLine={false}
          tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
          tickFormatter={format}
        />
        <ChartTooltip
          content={
            <ChartTooltipContent
              labelFormatter={(_, payload) =>
                new Intl.DateTimeFormat(LOCALE, {
                  dateStyle: "short",
                  timeStyle: "short",
                }).format(tooltipTimestamp(payload))
              }
              formatter={(value) => format(Number(value))}
            />
          }
        />
        <Area
          dataKey="value"
          type="monotone"
          stroke={colour}
          strokeWidth={1.5}
          fill={`url(#fill-${label})`}
          isAnimationActive={false}
          dot={false}
        />
      </AreaChart>
    </ChartContainer>
  )
}

/**
 * Which instant the tooltip's heading names.
 *
 * Recharts hands the formatter whatever it currently has, and on the first frame of a hover - and
 * for a chart being torn down - that is an empty list or an entry with no payload. Reaching for
 * `payload[0].payload.at` there yields `undefined`, and an `undefined` that reaches
 * `Intl.DateTimeFormat` is "Invalid Date" across the top of the tooltip.
 *
 * **Be precise about what this function changed, because the inline expression it replaced was not
 * broken.** It read `Number(payload?.[0]?.payload?.at ?? Date.now())`, and the optional chaining
 * plus `??` already covered every missing case - no payload, no entry, no `at`, an explicit `null`.
 * `steward/04` listed "null values in the chart tooltip" as a finding, and for those cases the
 * finding was already answered. Two things are actually new:
 *
 * 1. **It is testable.** As an argument to a JSX prop inside a chart inside a container, that
 *    expression could only be reached by rendering recharts in jsdom and hovering it. Nothing
 *    asserted any of it; `steward/04` found it by reading, which is how it stayed unasserted.
 * 2. **A value that is present but not a number now falls back too.** `at: "not a date"` passes
 *    `??`, becomes `NaN`, and renders as "Invalid Date" - the one hole the old expression left. It
 *    needs a malformed timestamp out of `/api/metrics`, so it is a guard against the backend and
 *    not against recharts.
 *
 * Falling back to *now* rather than to an empty string is deliberate: the tooltip is over a point
 * that does exist, and the frame in which the payload has not arrived yet is followed immediately
 * by one in which it has. A blank heading that flickers reads as a defect; a heading that is a
 * moment off for one frame does not.
 */
export function tooltipTimestamp(
  payload: readonly { payload?: { at?: unknown } }[] | undefined | null,
): number {
  const at = payload?.[0]?.payload?.at
  const parsed = Number(at)
  return at === undefined || at === null || Number.isNaN(parsed) ? Date.now() : parsed
}
