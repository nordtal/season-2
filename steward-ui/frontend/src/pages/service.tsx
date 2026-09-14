import { useEffect, useMemo, useRef, useState } from "react"
import { useParams } from "@tanstack/react-router"
import { ArrowDownToLine, Pause, Play, RotateCw, Search, Send, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { LOCALE, bytes, clock, percent, since } from "@/lib/format"
import { useConsole, useLogSearch, useService } from "@/lib/queries"
import { useLogStream, LIMIT } from "@/lib/use-log-stream"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { RecreateButton } from "@/components/steward/recreate"
import { DriftBadge, ServiceState, StatusBadge } from "@/components/steward/status"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Separator } from "@/components/ui/separator"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

/**
 * One service: what it is doing, what it is saying, and - for the four Minecraft servers - a way to
 * say something back.
 *
 * **The console's answer is not in the response.** `mc <command>` hands the line to the server's own
 * tmux session and the server prints its reply on its own console, which is this log. That is not a
 * limitation to apologise for: it is what makes a second admin's command visible to the first
 * instead of private.
 */
export function ServicePage() {
  const { name } = useParams({ from: "/services/$name" })
  const service = useService(name)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={name}
        note="Status, log window and - where there is one - the console."
        actions={<RecreateButton service={name} />}
      />

      {service.isPending ? (
        <Loading rows={3} />
      ) : service.error ? (
        <Failure error={service.error} onRetry={service.refetch} />
      ) : service.data === undefined ? (
        <Empty title="Unknown service" note={`"${name}" belongs to no container of the stack.`} />
      ) : (
        <ServiceHead service={service.data} />
      )}

      <LogPanel name={name} />

      {service.data?.hasConsole ? <ConsolePanel name={name} /> : null}
    </div>
  )
}

function ServiceHead({ service }: { service: NonNullable<ReturnType<typeof useService>["data"]> }) {
  return (
    <Card>
      <CardContent className="flex flex-wrap items-start gap-6 pt-6">
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
            State
          </span>
          <div className="flex items-center gap-2">
            <ServiceState state={service.state} health={service.health} />
            <DriftBadge drift={service.drift} />
            {service.hasConsole ? (
              <StatusBadge tone="idle" title="This service has a server console.">
                Console
              </StatusBadge>
            ) : null}
          </div>
          <span className="text-xs text-muted-foreground">{service.status}</span>
        </div>

        <Separator orientation="vertical" className="h-14" />

        <Stat label="Uptime" value={service.startedAt ? since(service.startedAt) : "–"} />
        <Stat label="RAM" value={bytes(service.memoryBytes)} hint="share of the host - no limit" />
        <Stat label="CPU" value={percent(service.cpuPercent)} />

        <div className="flex min-w-64 flex-col gap-1">
          <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
            Image
          </span>
          <code className="truncate text-sm">{service.image}</code>
          {service.digests?.length ? (
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
function LogPanel({ name }: { name: string }) {
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

  const bottom = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (follow && !stream.paused) bottom.current?.scrollIntoView({ block: "end" })
  }, [shown.length, follow, stream.paused])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Log</CardTitle>
        <CardDescription>
          Live from the container. Docker keeps up to 50 MB per container (5 × 10 MB) and nothing
          older; recreating the container resets that store.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Tabs defaultValue="window">
          <div className="flex flex-wrap items-center gap-2">
            <TabsList>
              <TabsTrigger value="window">Window</TabsTrigger>
              <TabsTrigger value="history">History</TabsTrigger>
            </TabsList>

            <div className="ml-auto flex items-center gap-2">
              <StreamState stream={stream} />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => stream.setPaused(!stream.paused)}
              >
                {stream.paused ? <Play aria-hidden /> : <Pause aria-hidden />}
                {stream.paused ? "Resume" : "Pause"}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setFollow((value) => !value)}
                aria-pressed={follow}
              >
                <ArrowDownToLine aria-hidden />
                {follow ? "Following" : "Free"}
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={stream.clear}>
                <Trash2 aria-hidden />
                Clear
              </Button>
            </div>
          </div>

          <TabsContent value="window" className="flex flex-col gap-3">
            <div className="flex items-center gap-2">
              <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
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
            <LogWindow lines={shown} bottom={bottom} />
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
              <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
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
        <RotateCw aria-hidden />
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
  bottom,
}: {
  lines: Array<{ seq: number; text: string; at: number }>
  bottom?: React.RefObject<HTMLDivElement | null>
}) {
  if (lines.length === 0) {
    return (
      <div className="flex h-96 items-center justify-center rounded-md border border-border bg-[#0a0a0a] text-sm text-muted-foreground">
        No lines yet.
      </div>
    )
  }
  return (
    <div className="h-96 overflow-auto rounded-md border border-border bg-[#0a0a0a] p-3 font-mono text-xs leading-5">
      {lines.map((line) => (
        <div key={line.seq} className="flex gap-3 whitespace-pre-wrap">
          {line.at ? (
            <span className="shrink-0 text-muted-foreground select-none">{clock(new Date(line.at))}</span>
          ) : null}
          <span className="min-w-0 break-all">{line.text}</span>
        </div>
      ))}
      <div ref={bottom} />
    </div>
  )
}

// --- the console --------------------------------------------------------------------------------

function ConsolePanel({ name }: { name: string }) {
  const console_ = useConsole(name)
  const [command, setCommand] = useState("")
  // The last few lines typed here, so that the up arrow does what a shell does. Not persisted: a
  // command history that survives a reload is a command history somebody else can read.
  const [history, setHistory] = useState<string[]>([])
  const [cursor, setCursor] = useState(-1)

  return (
    <Card id="console">
      <CardHeader>
        <CardTitle className="text-sm font-medium">Console</CardTitle>
        <CardDescription>
          One line into the server console. The answer appears in the log above - not here: the
          server writes it to its own console, and so every admin sees it.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="flex items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault()
            const line = command.trim()
            if (!line) return
            console_.mutate(line, {
              onSuccess: () => {
                toast.success(`"${line}" sent`, {
                  description: "The answer appears in the log window.",
                })
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
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <Label htmlFor="console-command">Command</Label>
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
              placeholder="z. B. list"
              className="font-mono"
              autoComplete="off"
              spellCheck={false}
            />
          </div>
          <Button type="submit" disabled={!command.trim() || console_.isPending}>
            <Send aria-hidden />
            Abschicken
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
