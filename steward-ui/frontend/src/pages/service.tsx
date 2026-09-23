import {
  ArrowClockwiseIcon,
  ArrowsClockwiseIcon,
  DotsThreeIcon,
  PlugIcon,
  PowerIcon,
  TerminalWindowIcon,
  WrenchIcon,
  ArrowLineDownIcon,
  CaretRightIcon,
  MagnifyingGlassIcon,
  PaperPlaneTiltIcon,
  PauseIcon,
  PlayIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { useEffect, useMemo, useRef, useState } from "react"
import { useNavigate, useParams, useSearch } from "@tanstack/react-router"
import { toast } from "sonner"

import { LOCALE, bytes, clock, count, percent, since } from "@/lib/format"
import { useConfigs, useConsole, useLogSearch, useMessageBundles, useService } from "@/lib/queries"
import { useLogStream, LIMIT } from "@/lib/use-log-stream"
import { ServiceConfiguration } from "@/components/steward/configuration"
import { ServiceMessages } from "@/components/steward/messages"
import { ServicePlugins } from "@/components/steward/plugins"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import { RecreateButton, useRecreateGate } from "@/components/steward/recreate"
import { ServiceOnlineLine } from "@/components/steward/online"
import { AskButton } from "@/pages/operations"
import { DriftBadge, ServiceState, StatusBadge } from "@/components/steward/status"
import { Empty, Failure, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Separator } from "@/components/ui/separator"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

/** What `/services/$name` keeps in its URL (steward/140). Console is the default and never written. */
export type ServiceSearch = { tab?: "settings" | "plugins"; file?: string }

export function serviceSearch(search: Record<string, unknown>): ServiceSearch {
  const answer: ServiceSearch = {}
  if (search.tab === "settings" || search.tab === "plugins") answer.tab = search.tab
  if (typeof search.file === "string" && search.file !== "") answer.file = search.file
  return answer
}

type Tab = "console" | "settings" | "plugins"

/**
 * Which tabs this service has, or `undefined` while that is not known yet.
 *
 * **A tab with nothing behind it is not there** (steward/140). Settings needs a config file or a
 * message bundle of this service - or a listing that failed, because then the failure is what the
 * tab has to show. Plugins needs the worker's `hasPlugins`, so the tab is decided by one answer
 * instead of being drawn and taken away again when `/plugins` comes back 404.
 */
export function useServiceTabs(name: string): Tab[] | undefined {
  const service = useService(name)
  const configs = useConfigs()
  const bundles = useMessageBundles()
  if (service.isPending || configs.isPending || bundles.isPending) return undefined
  const settings =
    configs.isError ||
    bundles.isError ||
    (configs.data ?? []).some(
      (file) => file.service === name || (file.service === "" && name === "steward-ui"),
    ) ||
    (bundles.data ?? []).some((bundle) => bundle.service === name)
  return [
    "console",
    ...(settings ? (["settings"] as const) : []),
    ...(service.data?.hasPlugins ? (["plugins"] as const) : []),
  ]
}

/**
 * One service: a head with its actions and who is on it, and three tabs under it (steward/140).
 *
 * Every service gets the same page. The six that are not Minecraft servers get the same head and
 * the same Console, and simply have fewer tabs - two layouts to keep would be the more expensive
 * half of that decision.
 */
export function ServicePage() {
  const { name } = useParams({ from: "/services/$name" })
  const search = useSearch({ from: "/services/$name" })
  const navigate = useNavigate({ from: "/services/$name" })
  const service = useService(name)
  const tabs = useServiceTabs(name)
  const tab: Tab = search.tab ?? "console"

  // A tab the service does not have - typed in, or left over from another service - goes back to
  // Console, replacing the entry so Back does not return to it.
  useEffect(() => {
    if (tabs && !tabs.includes(tab)) {
      void navigate({ search: {}, replace: true })
    }
  }, [tabs, tab, navigate])

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <PageHeader title={name} actions={<ServiceActions name={name} service={service.data} />} />
        <ServiceOnlineLine name={name} />
      </div>

      <Tabs
        value={tab}
        onValueChange={(next) =>
          // A tab change replaces the entry, so Back leaves the page rather than walking back
          // through every tab somebody looked at.
          void navigate({
            search: (previous) =>
              next === "console"
                ? {}
                : next === "settings"
                  ? { tab: "settings", file: previous.file }
                  : { tab: "plugins" },
            replace: true,
          })
        }
        className="gap-6"
      >
        {tabs ? (
          tabs.length > 1 ? (
            <TabsList className="w-full sm:w-fit">
              <TabsTrigger value="console" className="sm:px-3">
                <TerminalWindowIcon aria-hidden />
                Console
              </TabsTrigger>
              {tabs.includes("settings") ? (
                <TabsTrigger value="settings" className="sm:px-3">
                  <WrenchIcon aria-hidden />
                  <span className="sm:hidden">Settings</span>
                  <span className="max-sm:hidden">Settings &amp; Translations</span>
                </TabsTrigger>
              ) : null}
              {tabs.includes("plugins") ? (
                <TabsTrigger value="plugins" className="sm:px-3">
                  <PlugIcon aria-hidden />
                  Plugins
                </TabsTrigger>
              ) : null}
            </TabsList>
          ) : (
            // One tab is not a choice: postgres and the rest get the Console bar alone, so the page
            // still says what it is showing.
            <TabsList className="w-full sm:w-fit">
              <TabsTrigger value="console" className="sm:px-3">
                <TerminalWindowIcon aria-hidden />
                Console
              </TabsTrigger>
            </TabsList>
          )
        ) : (
          <Skeleton className="h-8 w-full rounded-lg sm:w-96" />
        )}

        <TabsContent value="console" className="flex flex-col gap-6">
          {/* The "unknown service" case is a 404 from the worker and arrives as a failure, which
              says the same thing with the name of the service in it. */}
          <QueryState query={service}>{(data) => <ServiceHead service={data} />}</QueryState>
          <LogPanel name={name} hasConsole={service.data?.hasConsole ?? false} />
        </TabsContent>

        <TabsContent value="settings" className="flex flex-col gap-6">
          <ServiceConfiguration service={name} />
          {/* Its own card, not a section of Configuration above: a message bundle has no YAML
              shape, no schema, and keys are merged one at a time (steward/48). */}
          <ServiceMessages service={name} />
        </TabsContent>

        <TabsContent value="plugins" className="flex flex-col gap-6">
          <ServicePlugins service={name} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

/**
 * Update, Take down (or Start), Recreate - each with its own symbol (steward/140).
 *
 * From `sm` up all three stand with their word. Below it Update stays as a symbol and the other
 * two move into a ⋯ menu, which opens exactly the confirmation the button would have: the dialogs
 * are the buttons' own, steered from here, not a second copy.
 */
function ServiceActions({
  name,
  service,
}: {
  name: string
  service?: NonNullable<ReturnType<typeof useService>["data"]>
}) {
  const [dialog, setDialog] = useState<"hold" | "recreate" | null>(null)
  const gate = useRecreateGate(name)
  // season-2-ops/125: Take down and Start are one switch, and which half is offered follows `hold`
  // and nothing else. While the row is loading neither is drawn: a Take down that turns into Start
  // under somebody's finger is worse than a button that arrives late.
  const hold = service === undefined ? undefined : service.hold ? "START" : "DOWN"
  const recreatable = name !== "steward-deployer"
  const HoldIcon = hold === "START" ? PlayIcon : PowerIcon
  const holdLabel = hold === "START" ? "Start" : "Take down"

  return (
    <div className="flex items-center gap-2">
      {/* season-2-ops/127: a run for this service alone, offered on every page - a run with
          nothing to install ends at "Nothing to do", which is a true answer. */}
      <AskButton
        kind="UPDATE"
        services={[name]}
        label="Update"
        size="sm"
        labelClassName="max-sm:hidden"
        className="max-sm:size-7 max-sm:px-0"
      />
      {hold ? (
        <AskButton
          kind={hold}
          services={[name]}
          label={holdLabel}
          variant={hold === "START" ? "default" : "outline"}
          size="sm"
          className="max-sm:hidden"
          open={dialog === "hold"}
          onOpenChange={(open) => setDialog(open ? "hold" : null)}
        />
      ) : null}
      <RecreateButton
        service={name}
        size="sm"
        className="max-sm:hidden"
        open={dialog === "recreate"}
        onOpenChange={(open) => setDialog(open ? "recreate" : null)}
      />
      {hold || recreatable ? (
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              className="sm:hidden"
              aria-label="More actions"
            >
              <DotsThreeIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {hold ? (
              <DropdownMenuItem onSelect={() => setDialog("hold")}>
                <HoldIcon aria-hidden />
                {holdLabel}
              </DropdownMenuItem>
            ) : null}
            {recreatable ? (
              <DropdownMenuItem disabled={gate.unavailable} onSelect={() => setDialog("recreate")}>
                <ArrowsClockwiseIcon aria-hidden />
                Recreate
              </DropdownMenuItem>
            ) : null}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
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
      <CardContent className="flex flex-wrap items-start gap-x-6 gap-y-4">
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium font-heading text-muted-foreground">
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
            {service ? <DriftBadge drift={service.drift} image={service.image} digests={service.digests} /> : null}
          </div>
        <Stat
          label="Uptime"
          value={service ? (service.startedAt ? since(service.startedAt) : "–") : undefined}
        />
        </div>


        <Separator orientation="vertical" className="hidden h-auto sm:block" />
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
      <StatusBadge tone="ok" tipContent="The log stream is up.">
        connected
      </StatusBadge>
    )
  }
  if (stream.state === "connecting") {
    return <StatusBadge tone="idle">connecting…</StatusBadge>
  }
  return (
    <span className="flex items-center gap-2">
      <StatusBadge tone="down" tipContent={stream.error ?? undefined}>
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
