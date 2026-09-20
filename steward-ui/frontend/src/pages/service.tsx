import {
  ArrowClockwiseIcon,
  ArrowLineDownIcon,
  CaretRightIcon,
  MagnifyingGlassIcon,
  PaperPlaneTiltIcon,
  PauseIcon,
  PlayIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useRef, useState } from "react"
import { useParams } from "@tanstack/react-router"
import { toast } from "sonner"

import { LOCALE, bytes, clock, count, percent, since } from "@/lib/format"
import { useConsole, useLogSearch, useService } from "@/lib/queries"
import { useLogStream, LIMIT } from "@/lib/use-log-stream"
import { ServiceConfiguration } from "@/components/steward/configuration"
import { ServiceMessages } from "@/components/steward/messages"
import { ServicePlugins } from "@/components/steward/plugins"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { RecreateButton } from "@/components/steward/recreate"
import { AskButton } from "@/pages/operations"
import { DriftBadge, ServiceState, StatusBadge } from "@/components/steward/status"
import { Empty, Failure, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Separator } from "@/components/ui/separator"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

/**
 * One service: what it is doing, what it is saying, and - for the four Minecraft servers - a way to
 * say something back.
 *
 * **The console is part of the log, not a card of its own.** `mc <command>` hands the line to the
 * server's own tmux session and the server prints its reply on its own console - which is this log,
 * three lines above where it was typed. Two cards made that into two places; a card titled
 * "Console" over a field labelled "Command" made it into four names for one input. It is one line
 * under the window now, which is also what it looks like on every server console there has ever
 * been. That the answer appears above rather than beside is not a limitation to apologise for: it
 * is what makes a second admin's command visible to the first instead of private.
 */
export function ServicePage() {
  const { name } = useParams({ from: "/services/$name" })
  const service = useService(name)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={name}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {/*
              season-2-ops/127: a run for this service alone. It is the same run - countdown, the
              wait in limbo, health, report - with a smaller scope, which is why it is the same
              button rather than a second mechanism.

              It is offered on every service page, including the ones a run has nothing to install
              for. That run ends at "Nothing to do", which is a true answer; hiding the button
              behind a list of updatable services kept in the browser would be a second opinion
              about what a run touches, and the worker is the only thing allowed to have one.
            */}
            <AskButton kind="UPDATE" services={[name]} label="Update" />
            {/*
              season-2-ops/125: Down and Start are one button, because they are one switch. Which
              half is offered follows the `hold` field and nothing else - not whether the container
              is running, because a service that crashed is stopped and must still offer Down, and
              a held service whose container somehow came back must still offer Start to clear the
              hold. While the row is loading neither is drawn: a Down button that turns into a
              Start button under somebody's finger is worse than a button that arrives late.
            */}
            {service.data === undefined ? null : service.data.hold ? (
              <AskButton kind="START" services={[name]} label="Start" variant="default" />
            ) : (
              <AskButton kind="DOWN" services={[name]} label="Put down" />
            )}
            <RecreateButton service={name} />
          </div>
        }
      />

      {/* The "unknown service" case used to be an empty state here. It is a 404 from the worker
          and arrives as a failure, which says the same thing with the name of the service in it -
          and an answered query with no body is not a state this route can produce. */}
      <QueryState query={service}>{(data) => <ServiceHead service={data} />}</QueryState>

      <LogPanel name={name} hasConsole={service.data?.hasConsole ?? false} />

      {/* season-2-ops/129. Under the log for the same reason the configuration is: the log is what
          somebody came here for, the plugin list is what they came here for once. It draws itself
          away entirely on a service with no plugins folder. */}
      <ServicePlugins service={name} />

      {/* Below the log on purpose. The log is what somebody came here for; the configuration is
          what they came here for once. */}
      <ServiceConfiguration service={name} />

      {/* Its own card, not a section of Configuration above: a message bundle has no YAML shape,
          no schema, and keys are merged one at a time rather than a whole file rewritten
          (steward/48). */}
      <ServiceMessages service={name} />
    </div>
  )
}

/**
 * The head of a service page, exported for its own test: steward/86's second half is a field that
 * must be *absent* rather than zero, and that is only observable on a rendered head.
 */
export function ServiceHead({
  service,
}: {
  /** Absent while `/api/services/{name}` is out. Every field below then draws its own shape. */
  service?: NonNullable<ReturnType<typeof useService>["data"]>
}) {
  return (
    <Card>
      <CardContent className="flex flex-wrap items-start gap-x-6 gap-y-4 pt-6">
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium text-muted-foreground">
            State
          </span>
          <div className="flex items-center gap-2">
            {/* The hold is part of the state badge since steward/134, rather than a second badge
                beside it: a service put down on purpose is not "exited" in red plus an explanation,
                it is one reading, and it is now the same reading the sidebar and the network view
                draw for it. Since and by moved into that badge's title with it. */}
            {service ? (
              <ServiceState state={service.state} health={service.health} hold={service.hold} />
            ) : (
              <Skeleton className="h-5 w-20 rounded-full" />
            )}
            {service ? <DriftBadge drift={service.drift} /> : null}
            {service?.hasConsole ? (
              <StatusBadge tone="idle" title="This service has a server console.">
                Console
              </StatusBadge>
            ) : null}
          </div>
          {service ? (
            <span className="text-xs text-muted-foreground">{service.status}</span>
          ) : (
            <SkeletonText className="w-48 text-xs" />
          )}
        </div>

        <Separator orientation="vertical" className="hidden h-14 sm:block" />

        <Stat
          label="Uptime"
          value={service ? (service.startedAt ? since(service.startedAt) : "–") : undefined}
        />
        <Stat
          label="RAM"
          value={service ? bytes(service.memoryBytes) : undefined}
          hint="share of the host - no limit"
        />
        <Stat label="CPU" value={service ? percent(service.cpuPercent) : undefined} />
        {/*
          steward/86, Till on 2026-09-18: the start page had the numbers and this page did not.
          Only the four services that carry one get the field at all - `players === undefined` means
          nobody has said, and a `0` in its place would be a claim the row does not make.
        */}
        {/* Not drawn while waiting either, and that is the lesser of two jumps: six of the ten
            services never carry a count, so a Players field on every page would arrive and then
            leave again on most of them. */}
        {service?.players === undefined ? null : (
          <Stat label="Players" value={count(service.players)} />
        )}

        <div className="flex w-full min-w-0 flex-col gap-1 sm:w-auto sm:min-w-64">
          <span className="text-xs font-medium text-muted-foreground">
            Image
          </span>
          {service ? (
            <code className="truncate text-sm">{service.image}</code>
          ) : (
            <SkeletonText className="text-sm" width="long" />
          )}
          {!service ? (
            <SkeletonText className="text-xs" width="medium" />
          ) : service.digests?.length ? (
            <code className="truncate text-xs text-muted-foreground" title={service.digests[0]}>
              {service.digests[0]}
            </code>
          ) : (
            <span className="text-xs text-muted-foreground">
              No registry digest - built here, published nowhere.
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

// --- the log window -----------------------------------------------------------------------------

/**
 * Two searches, and the difference between them is the whole point of the switch.
 *
 * *Window* filters what this browser already holds - instant, and blind to anything that scrolled
 * past before the page was opened. *History* asks steward-worker to grep what Docker still has on
 * disk: up to 50 MB per container, measured on this host, and **nothing older**, because nothing
 * older exists anywhere. Recreating the container starts that buffer again.
 */
function LogPanel({ name, hasConsole }: { name: string; hasConsole: boolean }) {
  const stream = useLogStream(name)
  const [filter, setFilter] = useState("")
  const [follow, setFollow] = useState(true)
  const search = useLogSearch(name)
  const [pattern, setPattern] = useState("")

  const shown = useMemo(() => {
    if (!filter.trim()) return stream.lines
    const needle = filter.toLowerCase()
    return stream.lines.filter((line) => line.text.toLowerCase().includes(needle))
  }, [stream.lines, filter])

  // The window scrolls itself, rather than a sentinel element asking the page to scroll it into
  // view. `scrollIntoView` walks up every scrolling ancestor, and the shell's scroll area is one of
  // them - so on a phone, where the log's own box is most of the screen, opening a service scrolled
  // the page down past its own heading and the Recreate button, every time a line arrived.
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!follow || stream.paused) return
    const pane = box.current
    if (pane) pane.scrollTop = pane.scrollHeight
  }, [shown.length, follow, stream.paused])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Log</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Tabs defaultValue="window">
          <div className="flex flex-wrap items-center gap-2">
            <TabsList>
              <TabsTrigger value="window">Window</TabsTrigger>
              <TabsTrigger value="history">History</TabsTrigger>
            </TabsList>

            {/*
              Three switches and a state, and on a phone there is room for the symbols only - so the
              words are hidden rather than the buttons, and every one carries its name for anything
              that is not a pair of eyes. "Free" was the old label for the second one and nobody
              knew what it meant; not following is "Manual".
            */}
            <div className="ml-auto flex items-center gap-1.5 sm:gap-2">
              <StreamState stream={stream} />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => stream.setPaused(!stream.paused)}
                aria-label={stream.paused ? "Resume the stream" : "Pause the stream"}
                title={stream.paused ? "Resume the stream" : "Pause the stream"}
              >
                {stream.paused ? <PlayIcon aria-hidden /> : <PauseIcon aria-hidden />}
                <span className="max-sm:hidden">{stream.paused ? "Resume" : "Pause"}</span>
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setFollow((value) => !value)}
                aria-pressed={follow}
                aria-label={follow ? "Following the newest line" : "Scrolling by hand"}
                title={follow ? "Following the newest line" : "Scrolling by hand"}
              >
                <ArrowLineDownIcon aria-hidden />
                <span className="max-sm:hidden">{follow ? "Following" : "Manual"}</span>
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={stream.clear}
                aria-label="Clear the window"
                title="Clear the window"
              >
                <TrashIcon aria-hidden />
                <span className="max-sm:hidden">Clear</span>
              </Button>
            </div>
          </div>

          <TabsContent value="window" className="flex flex-col gap-3">
            <div className="flex items-center gap-2">
              <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
                placeholder="Filter the window…"
                aria-label="Filter the window"
              />
              <span className="shrink-0 text-xs text-muted-foreground tnum">
                {shown.length} / {stream.lines.length}
              </span>
            </div>
            <LogWindow lines={shown} box={box} />
            <p className="text-xs text-muted-foreground">
              The window holds {LIMIT.toLocaleString(LOCALE)} lines.
              {stream.dropped > 0
                ? ` ${stream.dropped.toLocaleString(LOCALE)} older ones have dropped out.`
                : ""}
            </p>
          </TabsContent>

          <TabsContent value="history" className="flex flex-col gap-3">
            <form
              className="flex items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault()
                if (pattern.trim()) search.mutate(pattern.trim())
              }}
            >
              <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <Input
                value={pattern}
                onChange={(event) => setPattern(event.target.value)}
                placeholder="Search what Docker has in stock…"
                aria-label="Search the history"
              />
              <Button type="submit" size="sm" disabled={!pattern.trim() || search.isPending}>
                {search.isPending ? "Searching…" : "Search"}
              </Button>
            </form>

            {search.error ? (
              <Failure error={search.error} />
            ) : search.data === undefined ? (
              <Empty
                title="Not searched yet"
                note="This search reads what Docker has on the disk - it takes a moment and loads the daemon, which is why it only runs on a button press."
              />
            ) : search.data.lines.length === 0 ? (
              <Empty title="Nothing found" note={`"${pattern}" does not appear in the store.`} />
            ) : (
              <>
                <LogWindow lines={search.data.lines.map((text, index) => ({ seq: index, text, at: 0 }))} />
                {search.data.truncated ? (
                  <p className="text-xs text-warning">
                    Cut off at {search.data.limit} hits - there are more.
                  </p>
                ) : null}
              </>
            )}
          </TabsContent>
        </Tabs>

        {hasConsole ? <ConsoleLine name={name} /> : null}
      </CardContent>
    </Card>
  )
}

function StreamState({ stream }: { stream: ReturnType<typeof useLogStream> }) {
  if (stream.state === "open") {
    return (
      <StatusBadge tone="ok" title="The log stream is up.">
        connected
      </StatusBadge>
    )
  }
  if (stream.state === "connecting") {
    return <StatusBadge tone="idle">connecting…</StatusBadge>
  }
  return (
    <span className="flex items-center gap-2">
      <StatusBadge tone="down" title={stream.error ?? undefined}>
        disconnected
      </StatusBadge>
      <Button type="button" variant="outline" size="sm" onClick={stream.reconnect}>
        <ArrowClockwiseIcon aria-hidden />
        Reconnect
      </Button>
    </span>
  )
}

/**
 * The window itself.
 *
 * Not a table and not a virtualised list: 5 000 monospaced lines is what a browser draws without
 * help, and a virtualiser here would be a dependency and a scroll-position bug in exchange for
 * nothing measurable.
 */
function LogWindow({
  lines,
  box,
}: {
  lines: Array<{ seq: number; text: string; at: number }>
  box?: React.RefObject<HTMLDivElement | null>
}) {
  if (lines.length === 0) {
    return (
      <div className="flex h-72 items-center justify-center rounded-md border border-border bg-[#0a0a0a] text-sm text-muted-foreground sm:h-96">
        No lines yet.
      </div>
    )
  }
  return (
    <div
      ref={box}
      className="h-72 overflow-auto rounded-md border border-border bg-[#0a0a0a] p-2 font-mono text-[0.6875rem] leading-5 sm:h-96 sm:p-3 sm:text-xs"
    >
      {lines.map((line) => (
        <div key={line.seq} className="flex gap-3 whitespace-pre-wrap">
          {line.at ? (
            <span className="shrink-0 text-muted-foreground select-none">{clock(new Date(line.at))}</span>
          ) : null}
          <span className="min-w-0 break-all">{line.text}</span>
        </div>
      ))}
    </div>
  )
}

// --- the console, which is the bottom edge of the log ------------------------------------------

/**
 * One line into the server console, directly under the window its answer comes back in.
 *
 * The up arrow does what a shell's does. The history is not persisted, deliberately: a command
 * history that survives a reload is a command history the next person at this browser can read.
 */
function ConsoleLine({ name }: { name: string }) {
  const send = useConsole(name)
  const [command, setCommand] = useState("")
  const [history, setHistory] = useState<string[]>([])
  const [cursor, setCursor] = useState(-1)

  return (
    <form
      id="console"
      className="flex flex-col gap-1.5 border-t border-border pt-3"
      onSubmit={(event) => {
        event.preventDefault()
        const line = command.trim()
        if (!line) return
        send.mutate(line, {
          onSuccess: () => {
            toast.success(`"${line}" sent`, { description: "The answer appears in the window above." })
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
      <div className="flex items-center gap-2">
        <CaretRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <Input
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
          placeholder="e.g. list"
          aria-label="Send a line to the server console"
          className="font-mono"
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
        />
        <Button
          type="submit"
          size="icon"
          disabled={!command.trim() || send.isPending}
          aria-label="Send"
          title="Send"
        >
          <PaperPlaneTiltIcon aria-hidden />
        </Button>
      </div>
    </form>
  )
}
