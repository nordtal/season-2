import {
  ArrowUpIcon,
  DownloadSimpleIcon,
  KeyReturnIcon,
  ListMagnifyingGlassIcon,
  WarningCircleIcon,
  XIcon,
} from "@phosphor-icons/react"
import { useLayoutEffect, useMemo, useRef, useState } from "react"
import { toast } from "sonner"

import { continuesPrevious, parseLogLine } from "@/lib/log-line"
import type { Level, ParsedLine } from "@/lib/log-line"
import { useConsole } from "@/lib/queries"
import { DEFAULT_LIMIT, STEPS, useLogStream } from "@/lib/use-log-stream"
import type { LogEntry } from "@/lib/use-log-stream"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { cn } from "@/lib/utils"

/**
 * The Console tab's window: toolbar, the line into the server, and the log, newest on
 * top, in one dark rounded box that lies flat on the page.
 *
 * No Pause, no Following switch, no Clear, no connection badge: the stream reconnects by itself and
 * says so only in the window, and scrolling away is what stops the window from following - the
 * arrow that appears then is the way back.
 */
export function ServiceConsole({
  name,
  hasConsole,
  capacity,
}: {
  name: string
  hasConsole: boolean
  /** How many lines the worker can fill; the steps above it are not offered. */
  capacity: number | undefined
}) {
  const [limit, setLimit] = useState<number>(DEFAULT_LIMIT)
  const stream = useLogStream(name, limit)
  const [finding, setFinding] = useState(false)
  const [query, setQuery] = useState("")

  const steps = offeredSteps(capacity)

  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return stream.entries
    return stream.entries.filter(
      (entry) => entry.kind === "line" && entry.text.toLowerCase().includes(needle),
    )
  }, [stream.entries, query])

  const closeFind = () => {
    setFinding(false)
    setQuery("")
  }

  return (
    <section
      aria-label="Console"
      className="relative flex h-[65svh] flex-col overflow-hidden rounded-xl bg-[#0a0a0a] font-mono text-[0.6875rem] leading-5 text-white/85 sm:text-xs lg:h-[36rem]"
    >
      <div className="flex h-8 shrink-0 items-center gap-1 border-b border-white/5 px-1.5">
        {finding ? (
          <div className="flex min-w-0 flex-1 items-center gap-1.5">
            <ListMagnifyingGlassIcon className="size-3.5 shrink-0 text-white/45" aria-hidden />
            <input
              autoFocus
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Escape") closeFind()
              }}
              placeholder="Find"
              aria-label="Find in the console"
              className="min-w-0 flex-1 bg-transparent font-sans text-xs text-white outline-none placeholder:text-white/35"
              spellCheck={false}
            />
            {query.trim() ? (
              <span className="shrink-0 font-sans text-xs text-white/45 tabular-nums">{shown.length}</span>
            ) : null}
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={closeFind}
              aria-label="Close find"
              className="text-white/60 hover:bg-white/10 hover:text-white"
            >
              <XIcon aria-hidden />
            </Button>
          </div>
        ) : (
          <>
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={() => setFinding(true)}
              aria-label="Find"
              title="Find"
              className="text-white/60 hover:bg-white/10 hover:text-white"
            >
              <ListMagnifyingGlassIcon aria-hidden />
            </Button>
            <div className="flex-1" />
          </>
        )}
        <Select value={String(limit)} onValueChange={(value) => setLimit(Number(value))}>
          <SelectTrigger
            size="sm"
            aria-label="Lines"
            className="h-6 gap-1 border-0 bg-transparent px-1.5 font-sans text-xs text-white/60 shadow-none hover:bg-white/10 hover:text-white dark:bg-transparent dark:hover:bg-white/10"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent position="popper" align="end">
            {steps.map((step) => (
              <SelectItem key={step} value={String(step)}>
                {step.toLocaleString("en")} lines
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          onClick={() => download(name, shown)}
          disabled={!shown.some((entry) => entry.kind === "line")}
          aria-label="Download"
          title="Download"
          className="text-white/60 hover:bg-white/10 hover:text-white"
        >
          <DownloadSimpleIcon aria-hidden />
        </Button>
      </div>

      {hasConsole ? <ConsoleLine name={name} /> : null}

      {stream.failure ? (
        <div role="alert" className="flex flex-1 flex-col items-center justify-center gap-1 px-6 text-center font-sans">
          <WarningCircleIcon className="mb-1 size-5 text-white/60" aria-hidden />
          <p className="text-sm text-white/85">Can't reach the log.</p>
          <p className="text-xs text-white/40">{stream.failure}</p>
        </div>
      ) : (
        <LogLines entries={shown} />
      )}
    </section>
  )
}

/** The steps the worker can fill; the lowest is always there, the window says when it runs dry. */
export function offeredSteps(capacity: number | undefined): number[] {
  return STEPS.filter((step) => step === DEFAULT_LIMIT || (capacity ?? 0) >= step)
}

/** The raw lines the window holds, oldest first, as a file (the filter applies, the markers do not). */
function download(name: string, entries: LogEntry[]) {
  const text = entries
    .filter((entry) => entry.kind === "line")
    .map((entry) => entry.text)
    .join("\n")
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, "0")
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`
  const url = URL.createObjectURL(new Blob([`${text}\n`], { type: "text/plain" }))
  const link = document.createElement("a")
  link.href = url
  link.download = `${name}-${stamp}.log`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

const LEVEL_TEXT: Record<Level, string> = {
  TRACE: "text-white/40",
  DEBUG: "text-white/40",
  INFO: "text-white/85",
  WARN: "text-amber-300",
  ERROR: "text-red-400",
}

/** Parsed once per entry, not once per render: entries keep their identity while they stay. */
const parsed = new WeakMap<LogEntry, ParsedLine>()
function parse(entry: LogEntry): ParsedLine {
  let line = parsed.get(entry)
  if (!line) {
    line = parseLogLine(entry.text)
    parsed.set(entry, line)
  }
  return line
}

type Row = { entry: LogEntry; line: ParsedLine | null; level: Level | null }

/** Following means the top: within this many pixels of it the window moves with new lines. */
const FOLLOWING = 8
/** From here on the arrow back to the top shows. */
const AWAY = 48

function LogLines({ entries }: { entries: LogEntry[] }) {
  const box = useRef<HTMLDivElement>(null)
  const grid = useRef<HTMLDivElement>(null)
  const newest = useRef<number | null>(null)
  const [away, setAway] = useState(false)

  // The colour a stack trace inherits is the one of the line above it in time, so this walks the
  // log oldest first and then turns the list round for drawing.
  const rows = useMemo(() => {
    const out: Row[] = []
    let carried: Level | null = null
    for (const entry of entries) {
      if (entry.kind !== "line") {
        out.push({ entry, line: null, level: null })
        carried = null
        continue
      }
      const line = parse(entry)
      if (line.kind === "parsed") {
        carried = line.level
        out.push({ entry, line, level: line.level })
      } else {
        out.push({ entry, line, level: continuesPrevious(line.text) ? carried : null })
      }
    }
    return out.reverse()
  }, [entries])

  // A new block arrived on top. At the top, it slides in from above; scrolled away, the reading
  // position is held instead, and nothing moves under the reader's eyes.
  useLayoutEffect(() => {
    const pane = box.current
    const content = grid.current
    const top = rows[0]?.entry.seq ?? null
    const previous = newest.current
    newest.current = top
    if (!pane || !content || top === null || previous === null || top === previous) return
    const added = rows.findIndex((row) => row.entry.seq <= previous)
    // Not found: the window was refilled from a new connection, which is not an arrival.
    if (added <= 0) return
    const first = content.children[0] as HTMLElement | undefined
    const kept = content.children[added] as HTMLElement | undefined
    if (!first || !kept) return
    const height = kept.offsetTop - first.offsetTop
    if (height <= 0) return
    if (pane.scrollTop > FOLLOWING) {
      pane.scrollTop += height
      return
    }
    if (typeof content.animate !== "function") return
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return
    content.animate([{ transform: `translateY(-${height}px)` }, { transform: "translateY(0)" }], {
      duration: 250,
      easing: "ease-out",
    })
  }, [rows])

  if (rows.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center font-sans text-xs text-white/35">
        Waiting for the log…
      </div>
    )
  }

  return (
    <div className="relative min-h-0 flex-1">
      <div
        ref={box}
        onScroll={(event) => setAway(event.currentTarget.scrollTop > AWAY)}
        className="h-full overflow-auto px-2 pt-1.5 pb-16 [overflow-anchor:none] sm:px-3"
      >
        {/* On a phone the time and source stand in a line of their own above the text, which
            then has the full width; from `sm` up the three share one row as columns. */}
        <div ref={grid} className="grid grid-cols-1 gap-x-3 sm:grid-cols-[auto_auto_1fr]">
          {rows.map(({ entry, line, level }) =>
            line === null ? (
              <div key={entry.seq} className="col-span-full py-1 font-sans text-white/35">
                {entry.text}
              </div>
            ) : line.kind === "parsed" ? (
              <div
                key={entry.seq}
                className="col-span-full flex flex-wrap gap-x-3 pt-1 sm:grid sm:grid-cols-subgrid sm:pt-0"
              >
                <span className="text-white/35 tabular-nums select-none">{line.time}</span>
                <span className="max-w-[14ch] truncate text-white/45" title={line.source || undefined}>
                  {line.source}
                </span>
                <span
                  className={cn(
                    "min-w-0 basis-full break-words whitespace-pre-wrap sm:basis-auto",
                    LEVEL_TEXT[line.level],
                  )}
                >
                  {line.text}
                </span>
              </div>
            ) : (
              <div
                key={entry.seq}
                className={cn(
                  "break-words whitespace-pre-wrap",
                  // A line that carries on the one before it sits under the message, whether or
                  // not its head is still in the window; anything else has the whole width.
                  continuesPrevious(line.text) ? "max-sm:pl-3 sm:col-start-3" : "col-span-full",
                  level ? LEVEL_TEXT[level] : "text-white/85",
                )}
              >
                {line.text}
              </div>
            ),
          )}
        </div>
      </div>
      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-b from-transparent to-[#0a0a0a]" />
      {away ? (
        <button
          type="button"
          onClick={() => box.current?.scrollTo({ top: 0, behavior: "smooth" })}
          aria-label="Back to the newest line"
          title="Back to the newest line"
          className="absolute top-2 right-3 flex size-8 items-center justify-center rounded-full bg-white/10 text-white backdrop-blur hover:bg-white/20"
        >
          <ArrowUpIcon className="size-4" aria-hidden />
        </button>
      ) : null}
    </div>
  )
}

/**
 * One line into the server console, directly above the lines its answer comes back in.
 *
 * The up arrow does what a shell's does. The history is not persisted, deliberately: a command
 * history that survives a reload is a command history the next person at this browser can read.
 * No success toast: the answer appears two centimetres below. The error toast stays.
 */
function ConsoleLine({ name }: { name: string }) {
  const send = useConsole(name)
  const [command, setCommand] = useState("")
  const [history, setHistory] = useState<string[]>([])
  const [cursor, setCursor] = useState(-1)

  return (
    <form
      id="console"
      className="flex h-9 shrink-0 items-center gap-2 bg-white/[0.03] pr-1.5 pl-3"
      onSubmit={(event) => {
        event.preventDefault()
        const line = command.trim()
        if (!line) return
        send.mutate(line, {
          onSuccess: () => {
            setHistory((previous) => [line, ...previous].slice(0, 20))
            setCursor(-1)
            setCommand("")
          },
          onError: (error) => {
            toast.error("The line was not sent", { description: String(error) })
          },
        })
      }}
    >
      <span className="text-white/35 select-none" aria-hidden>
        ›
      </span>
      <input
        id="console-command"
        value={command}
        onChange={(event) => setCommand(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "ArrowUp" && history.length > 0) {
            event.preventDefault()
            const next = Math.min(cursor + 1, history.length - 1)
            setCursor(next)
            setCommand(history[next])
          } else if (event.key === "ArrowDown" && cursor >= 0) {
            event.preventDefault()
            const next = cursor - 1
            setCursor(next)
            setCommand(next < 0 ? "" : history[next])
          }
        }}
        placeholder="list"
        aria-label="Send a line to the server console"
        className="min-w-0 flex-1 bg-transparent text-white outline-none placeholder:text-white/25"
        autoComplete="off"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
      />
      <Button
        type="submit"
        variant="ghost"
        size="icon-xs"
        disabled={!command.trim() || send.isPending}
        aria-label="Send"
        title="Send"
        className="text-white hover:bg-white/10 hover:text-white"
      >
        <KeyReturnIcon aria-hidden />
      </Button>
    </form>
  )
}
