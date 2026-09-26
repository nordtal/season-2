import { useMemo, useState } from "react"
import {
  Area,
  AreaChart,
  ComposedChart,
  Line,
  LineChart,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"

import type { MetricPoint } from "@/lib/api"
import { LOCALE, bytes, percent } from "@/lib/format"
import { useMetrics } from "@/lib/queries"
import { Skeleton } from "@/components/steward/query-state"
import { Stat } from "@/components/steward/stat"

/**
 * The four proposed curves for a service page's CPU and RAM, each drawn from the same `useMetrics`
 * reads the live head makes. Deleted with the rest of `app/designs/` once one is picked.
 */

type Series = { at: number; value: number }[]
type Format = (value: number) => string

const HOUR_MINUTE = new Intl.DateTimeFormat(LOCALE, { hour: "2-digit", minute: "2-digit" })

function seriesOf(points: MetricPoint[] | undefined): Series | undefined {
  return points?.map((point) => ({ at: new Date(point.at).getTime(), value: point.value }))
}

/** `count` buckets of equal time, each its lowest, mean and highest sample. Empty buckets drop out. */
function buckets(series: Series, count: number) {
  if (series.length === 0) return []
  const from = series[0].at
  const width = Math.max(1, (series[series.length - 1].at - from) / count)
  const out: { at: number; band: [number, number]; mean: number }[] = []
  let index = 0
  for (let bucket = 0; bucket < count; bucket += 1) {
    const end = from + width * (bucket + 1)
    const slice: number[] = []
    while (index < series.length && (series[index].at < end || bucket === count - 1)) {
      slice.push(series[index].value)
      index += 1
    }
    if (slice.length === 0) continue
    out.push({
      at: from + width * (bucket + 0.5),
      band: [Math.min(...slice), Math.max(...slice)],
      mean: slice.reduce((sum, value) => sum + value, 0) / slice.length,
    })
  }
  return out
}

function peak(series: Series) {
  return series.reduce((top, point) => (point.value > top.value ? point : top), series[0])
}

/** The strip a curve will fill, shimmering, and nothing else - the same rule `Sparkline` keeps. */
function Waiting({ height }: { height: number }) {
  return <Skeleton style={{ height }} className="w-full" />
}

// --- A: a taller line, its peak marked ----------------------------------------------------------

export function TallLine({ label, value, points, format }: MetricProps) {
  const series = useMemo(() => seriesOf(points), [points])
  const top = series && series.length > 0 ? peak(series) : undefined
  const last = series?.[series.length - 1]
  return (
    <div className="flex min-w-0 flex-col gap-1.5 lg:w-56">
      <div className="flex items-end justify-between gap-2">
        <Stat label={label} value={value} />
        {top ? <span className="pb-1 text-xs text-muted-foreground tnum">max {format(top.value)}</span> : null}
      </div>
      {series === undefined ? (
        <Waiting height={56} />
      ) : (
        <div style={{ height: 56 }} aria-hidden>
          <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 200, height: 56 }}>
            <LineChart data={series} margin={{ top: 4, right: 4, bottom: 4, left: 0 }}>
              <XAxis dataKey="at" type="number" domain={["dataMin", "dataMax"]} hide />
              <YAxis domain={[0, "dataMax"]} hide />
              <Line
                dataKey="value"
                type="monotone"
                stroke="var(--chart-4)"
                strokeWidth={1.5}
                dot={false}
                isAnimationActive={false}
              />
              {top ? (
                <ReferenceDot x={top.at} y={top.value} r={2.5} fill="var(--muted-foreground)" stroke="none" />
              ) : null}
              {last ? (
                <ReferenceDot x={last.at} y={last.value} r={3} fill="var(--foreground)" stroke="var(--background)" />
              ) : null}
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}

// --- B: the range of each stretch as a band, its mean as the line ------------------------------

export function Band({ label, value, points }: MetricProps) {
  const data = useMemo(() => {
    const series = seriesOf(points)
    return series === undefined ? undefined : buckets(series, 48)
  }, [points])
  return (
    <div className="flex min-w-0 flex-col gap-1.5 lg:w-56">
      <Stat label={label} value={value} />
      {data === undefined ? (
        <Waiting height={48} />
      ) : (
        <div style={{ height: 48 }} aria-hidden>
          <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 200, height: 48 }}>
            <ComposedChart data={data} margin={{ top: 2, right: 0, bottom: 0, left: 0 }}>
              <XAxis dataKey="at" type="number" domain={["dataMin", "dataMax"]} hide />
              <YAxis domain={[0, "dataMax"]} hide />
              <Area
                dataKey="band"
                type="monotone"
                stroke="none"
                fill="var(--chart-4)"
                fillOpacity={0.3}
                isAnimationActive={false}
              />
              <Line
                dataKey="mean"
                type="monotone"
                stroke="var(--foreground)"
                strokeOpacity={0.7}
                strokeWidth={1.25}
                dot={false}
                isAnimationActive={false}
              />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}

// --- C: both in one chart, with a time axis ------------------------------------------------------

export function Together({
  cpu,
  memory,
  cpuValue,
  memoryValue,
}: {
  cpu?: MetricPoint[]
  memory?: MetricPoint[]
  cpuValue?: string
  memoryValue?: string
}) {
  const data = useMemo(() => {
    const load = seriesOf(cpu)
    const ram = seriesOf(memory)
    if (load === undefined || ram === undefined) return undefined
    // One row per bucket, both metrics in it: two series sampled together still do not share
    // timestamps to the millisecond, and one row per sample would draw each line with holes.
    const a = buckets(load, 96)
    const b = buckets(ram, 96)
    return a.map((row, index) => ({ at: row.at, cpu: row.mean, ram: b[index]?.mean }))
  }, [cpu, memory])
  const ticks = useMemo(() => hourTicks(data?.map((row) => row.at)), [data])
  return (
    <div className="col-span-2 flex min-w-0 flex-col gap-2 lg:flex-1">
      <div className="flex gap-6">
        <Stat label="CPU" value={cpuValue} className="border-l-2 border-[var(--chart-1)] pl-2" />
        <Stat label="RAM" value={memoryValue} className="border-l-2 border-[var(--chart-2)] pl-2" />
      </div>
      {data === undefined ? (
        <Waiting height={96} />
      ) : (
        <div style={{ height: 96 }} aria-hidden>
          <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 320, height: 96 }}>
            <LineChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: 4 }}>
              <XAxis
                dataKey="at"
                type="number"
                domain={["dataMin", "dataMax"]}
                ticks={ticks}
                tickFormatter={(at: number) => HOUR_MINUTE.format(at)}
                tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                tickLine={false}
                axisLine={{ stroke: "var(--border)" }}
                height={18}
              />
              <YAxis yAxisId="cpu" domain={[0, "dataMax"]} hide />
              <YAxis yAxisId="ram" domain={[0, "dataMax"]} hide />
              <Line
                yAxisId="cpu"
                dataKey="cpu"
                type="monotone"
                stroke="var(--chart-1)"
                strokeWidth={1.5}
                dot={false}
                isAnimationActive={false}
              />
              <Line
                yAxisId="ram"
                dataKey="ram"
                type="monotone"
                stroke="var(--chart-2)"
                strokeWidth={1.5}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}

/** Every second full hour inside the range - five labels over six hours, none crowding the ends. */
function hourTicks(times: number[] | undefined) {
  if (!times || times.length < 2) return undefined
  const hour = 3_600_000
  const out: number[] = []
  for (let at = Math.ceil(times[0] / hour) * hour; at < times[times.length - 1]; at += hour) {
    if (new Date(at).getHours() % 2 === 0) out.push(at)
  }
  return out
}

// --- D: one range for both, and a reading under the finger --------------------------------------

export const RANGES = [1, 6, 24] as const
export type Range = (typeof RANGES)[number]

export function RangeSwitch({ hours, onChange }: { hours: Range; onChange: (hours: Range) => void }) {
  return (
    <div role="tablist" aria-label="Range" className="flex w-fit rounded-lg border border-border p-0.5">
      {RANGES.map((option) => (
        <button
          key={option}
          type="button"
          role="tab"
          aria-selected={hours === option}
          onClick={() => onChange(option)}
          className={`min-h-control min-w-11 rounded-md px-3 text-sm tnum transition-colors duration-150 ease-out ${
            hours === option ? "bg-secondary text-foreground" : "text-muted-foreground hover:text-foreground"
          }`}
        >
          {option} h
        </button>
      ))}
    </div>
  )
}

export function Scrub({ label, value, points, format, colour }: MetricProps & { colour: string }) {
  const series = useMemo(() => seriesOf(points), [points])
  const [reading, setReading] = useState<{ at: number; value: number } | null>(null)
  return (
    <div className="flex min-w-0 flex-col gap-1.5 lg:w-56">
      <Stat
        label={reading ? `${label} ${HOUR_MINUTE.format(reading.at)}` : label}
        value={reading ? format(reading.value) : value}
      />
      {series === undefined ? (
        <Waiting height={40} />
      ) : (
        <div style={{ height: 40 }} className="touch-pan-y">
          <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 200, height: 40 }}>
            <AreaChart
              data={series}
              margin={{ top: 2, right: 0, bottom: 0, left: 0 }}
              onMouseMove={(state) => {
                const index = Number(state?.activeTooltipIndex)
                setReading(Number.isInteger(index) ? (series[index] ?? null) : null)
              }}
              onMouseLeave={() => setReading(null)}
            >
              <XAxis dataKey="at" type="number" domain={["dataMin", "dataMax"]} hide />
              <YAxis domain={[0, "dataMax"]} hide />
              {/* The reading moves up into the number, so the tooltip draws only its cursor. */}
              <Tooltip
                content={() => null}
                cursor={{ stroke: "var(--muted-foreground)", strokeWidth: 1 }}
                isAnimationActive={false}
              />
              <Area
                dataKey="value"
                type="monotone"
                stroke={colour}
                strokeWidth={1.5}
                fill={colour}
                fillOpacity={0.15}
                dot={false}
                activeDot={{ r: 3, fill: colour, stroke: "var(--background)" }}
                isAnimationActive={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}

export function useRangeMetrics(name: string, hours: number) {
  const cpu = useMetrics(name, "cpu_percent", hours)
  const memory = useMetrics(name, "memory_bytes", hours)
  return { cpu: cpu.data?.points, memory: memory.data?.points }
}

type MetricProps = {
  label: string
  value?: string
  points?: MetricPoint[]
  format: Format
}

export const formatCpu: Format = (value) => percent(value)
export const formatRam: Format = (value) => bytes(value)
