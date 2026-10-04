import { useMemo, useState } from "react"
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"

import type { MetricPoint } from "@/lib/api"
import { span, t } from "@/lib/texts"
import { Stat } from "@/components/steward/stat"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"

/** How far back a curve reaches, in minutes: the ranges the service page offers. */
export const RANGES = [2, 10, 60, 360, 720] as const
export type Range = (typeof RANGES)[number]

type Reading = { at: number; value: number }

/** The range of a page's curves, as a small select that stays out of the way. */
export function RangeSelect({ minutes, onChange }: { minutes: Range; onChange: (minutes: Range) => void }) {
  return (
    <Select
      value={String(minutes)}
      onValueChange={(next) => {
        const range = RANGES.find((option) => String(option) === next)
        if (range !== undefined) onChange(range)
      }}
    >
      <SelectTrigger
        size="sm"
        aria-label={t("steward.service-page.range")}
        className="-ml-1.5 h-6 border-transparent bg-transparent px-1.5 text-xs text-muted-foreground tnum hover:text-foreground dark:bg-transparent dark:hover:bg-transparent"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent position="popper" align="start" className="min-w-20">
        {RANGES.map((option) => (
          <SelectItem key={option} value={String(option)} className="tnum">
            {span(option * 60)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/**
 * One number with its curve below it; touching or hovering the curve moves the reading at that moment into the number.
 *
 * Without `label` it is the curve alone, for a tile that already draws its number.
 */
export function MetricChart({
  label,
  value,
  points,
  format,
  colour,
  height = 40,
  className,
  statClassName,
  valueClassName,
}: {
  label?: string
  /** The number now, absent while it is on its way. */
  value?: string
  /** Absent while the series is read, drawn as a strip of the same height. */
  points?: MetricPoint[]
  format: (value: number) => string
  colour: string
  height?: number
  className?: string
  /** Passed to the number's `Stat`, for a row that lays it out differently on a phone. */
  statClassName?: string
  valueClassName?: string
}) {
  const [reading, setReading] = useState<Reading | null>(null)
  return (
    <div className={className ?? "flex min-w-0 flex-col gap-1.5"}>
      {label === undefined ? null : (
        <Stat
          label={reading ? t("steward.service-page.reading", { metric: label, at: new Date(reading.at) }) : label}
          value={reading ? format(reading.value) : value}
          className={statClassName}
          valueClassName={valueClassName}
        />
      )}
      <Curve points={points} colour={colour} height={height} onReading={label === undefined ? null : setReading} />
    </div>
  )
}

/** Room above and below the data, never below zero, so a steady line does not lie along an edge. */
function fit([min, max]: readonly [number, number]): [number, number] {
  const pad = Math.max((max - min) * 0.2, Math.abs(max) * 0.05, Number.EPSILON)
  return [Math.max(0, min - pad), max + pad]
}

function Curve({
  points,
  colour,
  height,
  onReading,
}: {
  points?: MetricPoint[]
  colour: string
  height: number
  onReading: ((reading: Reading | null) => void) | null
}) {
  const series = useMemo(
    () => points?.map((point) => ({ at: new Date(point.at).getTime(), value: point.value })),
    [points],
  )
  if (series === undefined) return <Skeleton style={{ height }} className="w-full" />
  if (series.length === 0) return <div style={{ height }} aria-hidden />
  const follow = (state: { activeTooltipIndex?: unknown }) => {
    const point = series[Number(state.activeTooltipIndex)]
    onReading?.(isReading(point) ? point : null)
  }
  const leave = () => onReading?.(null)
  return (
    // The number above carries the reading, so the curve itself says nothing to a screen reader.
    <div style={{ height }} className="touch-pan-y" aria-hidden>
      {/* `initialDimension`, since jsdom measures zero by zero and recharts will not draw into that. */}
      <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 200, height }}>
        <AreaChart
          data={series}
          margin={{ top: 2, right: 0, bottom: 0, left: 0 }}
          onMouseMove={onReading ? follow : undefined}
          onTouchMove={onReading ? follow : undefined}
          onMouseLeave={onReading ? leave : undefined}
          onTouchEnd={onReading ? leave : undefined}
        >
          <XAxis dataKey="at" type="number" domain={["dataMin", "dataMax"]} hide />
          <YAxis domain={fit} hide />
          {/* The reading moves up into the number, so the tooltip draws only its cursor. */}
          {onReading ? (
            <Tooltip
              content={() => null}
              cursor={{ stroke: "var(--muted-foreground)", strokeWidth: 1 }}
              isAnimationActive={false}
            />
          ) : null}
          <Area
            dataKey="value"
            type="monotone"
            stroke={colour}
            strokeWidth={1.5}
            fill={colour}
            fillOpacity={0.15}
            dot={false}
            activeDot={onReading ? { r: 3, fill: colour, stroke: "var(--background)" } : false}
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

function isReading(value: unknown): value is Reading {
  return (
    typeof value === "object" &&
    value !== null &&
    "at" in value &&
    typeof value.at === "number" &&
    "value" in value &&
    typeof value.value === "number"
  )
}
