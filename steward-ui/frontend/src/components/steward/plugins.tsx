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
 * The plugins on one Minecraft server, in three lists, and the Modrinth search that adds one.
 *
 * **The groups are where a plugin comes from**, because that decides what can be done to it: a
 * Nordtal jar is built by this repository, a preinstalled one is given by the network, and only an
 * added one has a row somebody can delete. The trash button follows from the group, not from a
 * second rule.
 *
 * **A plugin is running or not installed, and the badge is the difference.** Installing writes the
 * row and the next update run that can fetch the jar brings it; a plugin the network gives that is
 * not on the disk is the same case from the other side. The worker lists both, and the badge says
 * "No 26.2 build" instead when the update check finds nothing to install.
 *
 * **Check for updates writes nothing.** It asks every source again and shows the answer on the rows;
 * installing it is an update run, which is the Update button in the header.
 */
export function ServicePlugins({ service }: { service: string }) {
  const plugins = usePlugins(service)
  const available = useAvailable()
  const refresh = useRefreshAvailable()
  const [adding, setAdding] = useState(false)

  // A service with no plugins folder - the bot, postgres, caddy - answers 404, and the honest
  // thing to draw for it is nothing at all. The worker owns that judgement (it is Topology), so
  // this asks rather than keeping a second list of which services have plugins.
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
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={refresh.isPending}
          onClick={() => refresh.mutate()}
        >
          {refresh.isPending ? (
            <SpinnerIcon className="animate-spin" aria-hidden />
          ) : (
            <ArrowsCounterClockwiseIcon aria-hidden />
          )}
          Check for updates
        </Button>
      </div>

      {unchecked ? (
        <p className="text-sm text-muted-foreground">Updates can&apos;t be checked right now.</p>
      ) : null}

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
        <ResponsiveDialogContent>
          <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>Add plugin</ResponsiveDialogTitle>
            <ResponsiveDialogDescription className="sr-only">Search Modrinth</ResponsiveDialogDescription>
          </ResponsiveDialogHeader>
          <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto">
            <Search service={service} loader={plugins.data?.loader} version={plugins.data?.gameVersion} />
          </div>
        </ResponsiveDialogContent>
      </ResponsiveDialog>
    </div>
  )
}

// --- what is on the server ----------------------------------------------------------------------

type Group = NonNullable<ServicePlugin["group"]>

const GROUP_ORDER: Group[] = ["nordtal", "preinstalled", "added"]

const GROUP_TITLES: Record<Group, string> = {
  nordtal: "Nordtal",
  preinstalled: "Preinstalled",
  added: "Added",
}

/** Three absent rows: the count an ordinary Paper service here settles at. */
const WAITING_PLUGINS = [undefined, undefined, undefined]

/**
 * The plugins in their three lists, empty ones left out. Each is alphabetical, except that a
 * Nordtal plugin the worker ranks comes first in its rank's order.
 */
export function groupPlugins(plugins: ServicePlugin[]): [Group, ServicePlugin[]][] {
  const rank = (plugin: ServicePlugin) => plugin.rank ?? Number.MAX_SAFE_INTEGER
  return GROUP_ORDER.map((group): [Group, ServicePlugin[]] => [
    group,
    plugins
      .filter((plugin) => (plugin.group ?? "added") === group)
      .sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name, undefined, { sensitivity: "base" })),
  ]).filter(([, rows]) => rows.length > 0)
}

/**
 * The badge on a plugin that is not on the disk: no build for this Minecraft version when the update
 * check says nothing resolves, and otherwise simply that it is not installed.
 */
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

/**
 * What the update check says about one running plugin, or nothing. Nothing is also the answer when
 * the check has not answered, and when it could not tell - a plugin it does not track is not "up to
 * date", it is simply not something this line talks about.
 */
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
  absence,
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
      {plugin && absence ? (
        <StatusBadge tone="idle">{absence}</StatusBadge>
      ) : status ? (
        <span
          className={
            status.tone === "warn"
              ? "shrink-0 text-xs text-warning tnum"
              : "shrink-0 text-xs text-muted-foreground"
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

/**
 * The picture in front of a row: the mark for a Nordtal jar, Modrinth's icon for a Modrinth plugin,
 * and nothing for a jar from anywhere else - but the room for it stays, so the names line up.
 */
function Tile({ plugin }: { plugin?: ServicePlugin }) {
  if (!plugin) return <Skeleton className="size-9 shrink-0 rounded-md" />
  if (plugin.group === "nordtal") return <StewardMark className="size-9 shrink-0" />
  if (plugin.projectId) return <Thumbnail url={plugin.iconUrl} alt={plugin.name} className="size-9 rounded-md" />
  return <div className="size-9 shrink-0" aria-hidden />
}

/**
 * Removing, with the folder named in the dialog.
 *
 * Till chose that removal deletes the data folder as well, against the objection that
 * `plugins/<name>/` is the only hand-kept thing in the whole installation - and the confirmation
 * is the other half of that decision, not a softening of it. So the name of the directory is what
 * the dialog is about, and when the worker could not read it out of the jar the dialog says that
 * instead of inventing one.
 */
function RemoveButton({
  service,
  plugin,
  artifact,
}: {
  service: string
  plugin: ServicePlugin
  artifact: string
}) {
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
                      description: answer.deleted.length
                        ? answer.deleted.join(", ")
                        : "It had not been installed yet.",
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
 * What the confirmation says, as a function, because it is the part of this feature that must not
 * be got wrong and a sentence inside JSX cannot be tested.
 *
 * Three cases and they are three different promises. A running plugin whose data folder the worker
 * read: both names appear, and `plugins/<name>/` is the one that matters - it is the only
 * hand-edited directory in the whole installation. A running plugin whose jar carried no readable
 * descriptor: the folder is **not** named, because nobody verified it, and the sentence says so
 * rather than inventing one. A plugin that is not installed: there is no jar and no folder, only the row.
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

// --- finding one --------------------------------------------------------------------------------

/**
 * The Modrinth search, filtered to this service's loader and Minecraft version.
 *
 * The filter is not a convenience: a list somebody installs from must not contain a plugin that
 * cannot run here. The proxy therefore sees a much shorter list than the backends do, which is the
 * intended answer rather than a shortcoming.
 */
/** Three absent hits - the first screenful of a Modrinth answer, and nothing said about it. */
const WAITING_HITS: (PluginHit | undefined)[] = [undefined, undefined, undefined]

function Search({
  service,
  loader,
  version,
}: {
  service: string
  loader?: string
  version?: string
}) {
  const [typed, setTyped] = useState("")
  const [query, setQuery] = useState("")
  const install = useInstallPlugin(service)

  // Typing is not a request. Modrinth is somebody else's API and every keystroke would be a call
  // to it; a third of a second is under the time it takes to reach for the mouse.
  useEffect(() => {
    const timer = setTimeout(() => setQuery(typed.trim()), 300)
    return () => clearTimeout(timer)
  }, [typed])

  const results = usePluginSearch(service, query, loader !== undefined)

  return (
    <>
      <div className="flex items-center gap-2">
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
    </>
  )
}

function InstallButton({
  hit,
  install,
}: {
  hit: PluginHit
  install: ReturnType<typeof useInstallPlugin>
}) {
  // Two reasons a plugin cannot be added and they are not the same sentence, so they are not the
  // same badge: one is already here because somebody added it, the other is here because the
  // network gives it and nothing may take it away.
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

// --- the two small things both lists use ----------------------------------------------------------

/**
 * The thumbnail, straight from `cdn.modrinth.com`.
 *
 * Till's choice, 2026-09-19: the browser loads it from Modrinth rather than this host proxying it.
 * The consequence worth knowing is that any Content-Security-Policy in front of steward-ui has to
 * allow that host, or these stay blank and only whoever opens the console finds out why. The worker
 * refuses to store a URL pointing anywhere else, so what arrives here is always that one host.
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
  // Two different blanks, deliberately: a project with no icon is a flat square, and a row that
  // has not been told yet shimmers. They used to be the same square.
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
      // A project that pulls its icon breaks the row's alignment otherwise, and a broken image
      // icon says nothing a blank square does not.
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
