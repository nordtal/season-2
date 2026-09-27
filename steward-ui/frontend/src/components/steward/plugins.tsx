import {
  ArrowSquareOutIcon,
  ArrowsCounterClockwiseIcon,
  MagnifyingGlassIcon,
  PlusIcon,
  SpinnerIcon,
  TrashIcon,
} from "@phosphor-icons/react"
import { useEffect, useState } from "react"
import { toast } from "sonner"

import { ApiError } from "@/lib/api"
import type { AvailableChange, PluginHit, ServicePlugin } from "@/lib/api"
import { count } from "@/lib/format"
import {
  useAvailable,
  useInstallPlugin,
  usePluginSearch,
  usePlugins,
  useRefreshAvailable,
  useRemovePlugin,
} from "@/lib/queries"
import { StewardMark } from "@/app/steward-mark"
import { Button } from "@/components/ui/button"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Input } from "@/components/ui/input"
import { Empty, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"

/**
 * The plugins on one Minecraft server in three lists by origin, and the Modrinth search that adds one.
 *
 * Only an added plugin can be removed. Check for updates writes nothing; installing is an update run.
 */
export function ServicePlugins({ service }: { service: string }) {
  const plugins = usePlugins(service)
  const available = useAvailable()
  const refresh = useRefreshAvailable()
  const [adding, setAdding] = useState(false)

  /** A service with no plugins folder answers 404 and draws nothing; the worker decides which have one. */
  if (plugins.error instanceof ApiError && plugins.error.status === 404) return null

  // Unknown is not "up to date": when the reading failed, no row says anything about updates.
  const unchecked = available.isError || refresh.isError
  const changes = unchecked ? undefined : available.data?.changes

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-2">
        <Button type="button" variant="outline" size="sm" onClick={() => setAdding(true)}>
          <PlusIcon aria-hidden />
          Add plugin
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={refresh.isPending} onClick={() => refresh.mutate()}>
          {refresh.isPending ? (
            <SpinnerIcon className="animate-spin" aria-hidden />
          ) : (
            <ArrowsCounterClockwiseIcon aria-hidden />
          )}
          Check for updates
        </Button>
      </div>

      {unchecked ? <p className="text-sm text-muted-foreground">Updates can&apos;t be checked right now.</p> : null}

      <QueryState query={plugins}>
        {(answer) =>
          answer && !answer.mounted ? (
            <Empty title="No volume" />
          ) : answer && answer.plugins.length === 0 ? (
            <Empty title="Nothing installed" />
          ) : answer ? (
            <div className="flex flex-col gap-4">
              {groupPlugins(answer.plugins).map(([group, rows]) => (
                <section key={group} className="flex flex-col">
                  <h3 className="text-xs text-muted-foreground">{GROUP_TITLES[group]}</h3>
                  <ul className="flex flex-col">
                    {rows.map((plugin) => (
                      <PluginRow
                        key={plugin.fileName ?? plugin.artifact ?? plugin.name}
                        service={service}
                        plugin={plugin}
                        status={pluginStatus(service, plugin, changes, answer.gameVersion)}
                        absence={absence(service, plugin, changes, answer.gameVersion)}
                      />
                    ))}
                  </ul>
                </section>
              ))}
            </div>
          ) : (
            <ul className="flex flex-col">
              {WAITING_PLUGINS.map((_, index) => (
                <PluginRow key={index} service={service} />
              ))}
            </ul>
          )
        }
      </QueryState>

      <ResponsiveDialog open={adding} onOpenChange={setAdding}>
        {/* The search stays put and only the results scroll, flush to the dialog's edge. */}
        <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg" data-testid="add-plugin">
          <ResponsiveDialogHeader className="px-4 pt-4 pb-3">
            <ResponsiveDialogTitle>Add plugin</ResponsiveDialogTitle>
            <ResponsiveDialogDescription className="sr-only">Search Modrinth</ResponsiveDialogDescription>
          </ResponsiveDialogHeader>
          <Search service={service} loader={plugins.data?.loader} version={plugins.data?.gameVersion} />
        </ResponsiveDialogContent>
      </ResponsiveDialog>
    </div>
  )
}

type Group = NonNullable<ServicePlugin["group"]>

const GROUP_ORDER: Group[] = ["nordtal", "preinstalled", "added"]

const GROUP_TITLES: Record<Group, string> = {
  nordtal: "Nordtal",
  preinstalled: "Preinstalled",
  added: "Added",
}

/** Three absent rows: the count an ordinary Paper service here settles at. */
const WAITING_PLUGINS = [undefined, undefined, undefined]

/** Sort key within a list: a Nordtal plugin the worker ranks comes first, in rank order. */
function rank(plugin: ServicePlugin): number {
  return plugin.rank ?? Number.MAX_SAFE_INTEGER
}

/** The plugins in their three lists, empty ones left out; each alphabetical after the ranked ones. */
export function groupPlugins(plugins: ServicePlugin[]): [Group, ServicePlugin[]][] {
  return GROUP_ORDER.map((group): [Group, ServicePlugin[]] => [
    group,
    plugins
      .filter((plugin) => (plugin.group ?? "added") === group)
      .toSorted((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name, undefined, { sensitivity: "base" })),
  ]).filter(([, rows]) => rows.length > 0)
}

/** The badge on a plugin not on the disk: no build for this version, or simply not installed. */
export function absence(
  service: string,
  plugin: ServicePlugin,
  changes: AvailableChange[] | undefined,
  gameVersion?: string,
): string | undefined {
  if (plugin.running) return undefined
  const change = (changes ?? []).find((it) => it.service === service && it.artifact === plugin.artifact)
  return change?.status === "UNSUPPORTED" ? (gameVersion ? `No ${gameVersion} build` : "No build") : "Not installed"
}

/** The version in a jar's name: what follows the last `-` of the stem, as `JarName` reads it. */
export function versionOf(fileName?: string): string | undefined {
  if (!fileName) return undefined
  const stem = fileName.replace(/\.jar$/i, "")
  const dash = stem.lastIndexOf("-")
  return dash > 0 && dash < stem.length - 1 ? stem.slice(dash + 1) : undefined
}

export type PluginStatus = { tone: "idle" | "warn"; text: string }

/** What the update check says about one running plugin, or nothing when it has no answer for it. */
export function pluginStatus(
  service: string,
  plugin: ServicePlugin,
  changes: AvailableChange[] | undefined,
  gameVersion?: string,
): PluginStatus | undefined {
  if (!changes || !plugin.running || !plugin.fileName) return undefined
  const change = changes.find((it) => it.service === service && it.installed === plugin.fileName)
  if (!change) return undefined
  switch (change.status) {
    case "UP_TO_DATE":
      return { tone: "idle", text: "up to date" }
    case "OUTDATED": {
      if (change.held) return { tone: "idle", text: "held back" }
      const from = plugin.version ?? versionOf(plugin.fileName)
      const to = versionOf(change.fileName) ?? change.version
      return { tone: "warn", text: from && to ? `${from} → ${to}` : "update available" }
    }
    case "UNSUPPORTED":
      return { tone: "idle", text: gameVersion ? `no ${gameVersion} build` : "no build" }
    default:
      return undefined
  }
}

function PluginRow({
  service,
  plugin,
  status,
  absence: absenceText,
}: {
  service: string
  plugin?: ServicePlugin
  status?: PluginStatus
  absence?: string
}) {
  const version = plugin ? (plugin.version ?? versionOf(plugin.fileName)) : undefined
  return (
    <li className="flex min-h-14 items-center gap-3">
      <Tile plugin={plugin} />
      <div className="flex min-w-0 flex-1 flex-col">
        {plugin ? (
          <>
            <span className="truncate text-sm font-medium">{plugin.name}</span>
            {version ? <span className="truncate text-xs text-muted-foreground tnum">{version}</span> : null}
          </>
        ) : (
          <>
            <SkeletonText className="text-sm" width="short" />
            <SkeletonText className="text-xs" width="short" />
          </>
        )}
      </div>
      {plugin && absenceText ? (
        <StatusBadge tone="idle">{absenceText}</StatusBadge>
      ) : status ? (
        <span
          className={
            status.tone === "warn" ? "shrink-0 text-xs text-warning tnum" : "shrink-0 text-xs text-muted-foreground"
          }
        >
          {status.text}
        </span>
      ) : null}
      {plugin?.projectId ? <Link url={plugin.pageUrl} title={plugin.name} /> : null}
      {plugin?.group === "added" && plugin.removable && plugin.artifact ? (
        <RemoveButton service={service} plugin={plugin} artifact={plugin.artifact} />
      ) : null}
    </li>
  )
}

/** The mark for a Nordtal jar, Modrinth's icon, or an empty slot of the same size so names line up. */
function Tile({ plugin }: { plugin?: ServicePlugin }) {
  if (!plugin) return <Skeleton className="size-9 shrink-0 rounded-md" />
  if (plugin.group === "nordtal") return <StewardMark className="size-9 shrink-0" />
  if (plugin.projectId) return <Thumbnail url={plugin.iconUrl} alt={plugin.name} className="size-9 rounded-md" />
  return <div className="size-9 shrink-0" aria-hidden />
}

/** Removes a plugin after a dialog naming the data folder it deletes as well. */
function RemoveButton({ service, plugin, artifact }: { service: string; plugin: ServicePlugin; artifact: string }) {
  const [open, setOpen] = useState(false)
  const remove = useRemovePlugin(service)

  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        onClick={() => setOpen(true)}
        aria-label={`Remove ${plugin.name}`}
        title={`Remove ${plugin.name}`}
      >
        <TrashIcon aria-hidden />
      </Button>
      <ResponsiveDialog open={open} onOpenChange={setOpen}>
        <ResponsiveDialogContent>
          <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>Remove {plugin.name}?</ResponsiveDialogTitle>
            <ResponsiveDialogDescription>{removalSentence(plugin)}</ResponsiveDialogDescription>
          </ResponsiveDialogHeader>
          <ResponsiveDialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={remove.isPending}
              onClick={() =>
                remove.mutate(artifact, {
                  onSuccess: (answer) => {
                    setOpen(false)
                    toast.success(`${plugin.name} removed`, {
                      description: answer.deleted.length ? answer.deleted.join(", ") : "It had not been installed yet.",
                    })
                  },
                  onError: (error) => toast.error("Not removed", { description: String(error) }),
                })
              }
            >
              Remove
            </Button>
          </ResponsiveDialogFooter>
        </ResponsiveDialogContent>
      </ResponsiveDialog>
    </>
  )
}

/**
 * What the removal confirmation says, testable outside JSX.
 *
 * The data folder is named only when the worker read it from the jar; an absent plugin loses only its row.
 */
export function removalSentence(plugin: ServicePlugin): string {
  if (!plugin.running) {
    return "It is not installed yet, so only the entry goes. Nothing on the disk is touched."
  }
  const jar = plugin.fileName ?? "The jar"
  if (!plugin.dataFolder) {
    return `${jar} is deleted. Its data folder could not be read out of the jar, so nothing else under plugins/ is touched.`
  }
  return `${jar} and plugins/${plugin.dataFolder}/ are deleted. That folder holds this plugin's configuration and its data.`
}

/** Three absent hits, the first screenful of a Modrinth answer. */
const WAITING_HITS: (PluginHit | undefined)[] = [undefined, undefined, undefined]

/** The Modrinth search, filtered to this service's loader and Minecraft version so every hit can run here. */
function Search({ service, loader, version }: { service: string; loader?: string; version?: string }) {
  const [typed, setTyped] = useState("")
  const [query, setQuery] = useState("")
  const install = useInstallPlugin(service)

  /** Debounced a third of a second, so typing does not call Modrinth on every keystroke. */
  useEffect(() => {
    const timer = setTimeout(() => setQuery(typed.trim()), 300)
    return () => clearTimeout(timer)
  }, [typed])

  const results = usePluginSearch(service, query, loader !== undefined)

  return (
    <>
      <div className="flex items-center gap-2 px-4 pb-3">
        <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <Input
          value={typed}
          onChange={(event) => setTyped(event.target.value)}
          placeholder="Search Modrinth…"
          aria-label="Search Modrinth"
        />
        {loader && version ? (
          <span className="shrink-0 text-xs text-muted-foreground">
            {loader} {version}
          </span>
        ) : null}
      </div>

      <div className="max-h-[60vh] overflow-y-auto border-t border-border px-4 py-3" data-testid="add-plugin-results">
        <QueryState
          query={results}
          isEmpty={(answer) => answer.hits.length === 0}
          empty={{ title: "Nothing found", note: `Nothing on ${loader} for ${version}.` }}
        >
          {(answer) => (
            <ul className="flex flex-col gap-2">
              {(answer?.hits ?? WAITING_HITS).map((hit, index) => (
                <li
                  key={hit?.projectId ?? index}
                  className="flex items-center gap-3 border-b border-border pb-2 last:border-b-0 last:pb-0"
                >
                  <Thumbnail url={hit?.iconUrl} alt={hit?.title ?? ""} waiting={!hit} />
                  <div className="flex min-w-0 flex-1 flex-col">
                    {hit ? (
                      <>
                        <span className="truncate text-sm">{hit.title}</span>
                        <span className="truncate text-xs text-muted-foreground">{hit.description}</span>
                        <span className="text-xs text-muted-foreground tnum">{count(hit.downloads)}</span>
                      </>
                    ) : (
                      <>
                        <SkeletonText className="text-sm" width="short" />
                        <SkeletonText className="text-xs" width="long" />
                        <SkeletonText className="text-xs" width="short" />
                      </>
                    )}
                  </div>
                  {hit ? (
                    <>
                      <Link url={hit.pageUrl} title={hit.title} />
                      <InstallButton hit={hit} install={install} />
                    </>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </QueryState>
      </div>
    </>
  )
}

function InstallButton({ hit, install }: { hit: PluginHit; install: ReturnType<typeof useInstallPlugin> }) {
  /** Already added and given by the network are different reasons, so they get different badges. */
  if (hit.fixed) {
    return (
      <StatusBadge tone="ok" tipContent="The network gives this plugin. It cannot be removed.">
        given
      </StatusBadge>
    )
  }
  if (hit.added) {
    return <StatusBadge tone="idle">added</StatusBadge>
  }
  return (
    <Button
      type="button"
      variant="outline"
      size="icon"
      disabled={install.isPending}
      aria-label={`Install ${hit.title}`}
      title={`Install ${hit.title}`}
      onClick={() =>
        install.mutate(
          { projectId: hit.projectId, slug: hit.slug, title: hit.title, iconUrl: hit.iconUrl },
          {
            onSuccess: (answer) =>
              toast.success(`${hit.title} added`, {
                description: `${answer.fileName} arrives with the next update run.`,
              }),
            onError: (error) => toast.error("Not added", { description: String(error) }),
          },
        )
      }
    >
      <PlusIcon aria-hidden />
    </Button>
  )
}

/**
 * The thumbnail, loaded straight from `cdn.modrinth.com`.
 *
 * A Content-Security-Policy in front of steward-ui must allow that host; the worker stores no other.
 */
function Thumbnail({
  url,
  alt,
  waiting,
  className = "size-8 rounded-sm",
}: {
  url?: string
  alt: string
  waiting?: boolean
  className?: string
}) {
  /** No icon is a flat square; a row still waiting shimmers. */
  if (waiting) {
    return <Skeleton className={`${className} shrink-0`} />
  }
  if (!url) {
    return <div className={`${className} shrink-0 bg-muted`} aria-hidden />
  }
  return (
    <img
      src={url}
      alt={alt}
      loading="lazy"
      className={`${className} shrink-0 object-cover`}
      /** A broken icon is hidden, keeping its space so the row stays aligned. */
      onError={(event) => {
        event.currentTarget.style.visibility = "hidden"
      }}
    />
  )
}

function Link({ url, title }: { url?: string; title: string }) {
  if (!url) return null
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer noopener"
      aria-label={`${title} on Modrinth`}
      title={`${title} on Modrinth`}
      className="shrink-0 text-muted-foreground hover:text-foreground"
    >
      <ArrowSquareOutIcon aria-hidden />
    </a>
  )
}
