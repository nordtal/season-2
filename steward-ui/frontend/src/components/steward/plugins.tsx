import { ArrowSquareOutIcon, MagnifyingGlassIcon, PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { useEffect, useState } from "react"
import { toast } from "sonner"

import { ApiError } from "@/lib/api"
import type { PluginHit, ServicePlugin } from "@/lib/api"
import { count } from "@/lib/format"
import { usePluginSearch, usePlugins, useInstallPlugin, useRemovePlugin } from "@/lib/queries"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { StatusBadge } from "@/components/steward/status"

/**
 * The plugins on one Minecraft server, and the Modrinth search that adds one (season-2-ops/129).
 *
 * **Two lists in one, and the badge is the whole point.** A plugin is *running* when its jar is in
 * the volume and *pre-booked* when it is only a row: installing writes the row and the next update
 * run fetches the jar, with the same countdown and the same report as every other change. A list
 * that drew both the same way would be claiming the server runs something it does not have.
 *
 * **What has no remove button is what has no row.** The plugins the network gives are named in
 * `Topology.SERVICES`, in Java, inside a jar that is already built - so there is nothing to delete
 * and the button is absent rather than disabled. That is the same fact from the other side, not a
 * second rule the interface has to remember.
 */
export function ServicePlugins({ service }: { service: string }) {
  const plugins = usePlugins(service)

  // A service with no plugins folder - the bot, postgres, caddy - answers 404, and the honest
  // thing to draw for it is nothing at all. The worker owns that judgement (it is Topology), so
  // this asks rather than keeping a second list of which services have plugins.
  if (plugins.error instanceof ApiError && plugins.error.status === 404) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Plugins</CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="installed">
          <TabsList>
            <TabsTrigger value="installed">Installed</TabsTrigger>
            <TabsTrigger value="add">Add</TabsTrigger>
          </TabsList>

          <TabsContent value="installed" className="flex flex-col gap-3">
            {plugins.isPending ? (
              <Loading rows={3} />
            ) : plugins.error ? (
              <Failure error={plugins.error} onRetry={plugins.refetch} />
            ) : !plugins.data.mounted ? (
              <Empty
                title="No volume"
                note="steward-worker cannot see this service's folder, so it cannot say what is in it."
              />
            ) : plugins.data.plugins.length === 0 ? (
              <Empty title="Nothing installed" />
            ) : (
              <ul className="flex flex-col gap-2">
                {plugins.data.plugins.map((plugin) => (
                  <InstalledRow key={plugin.fileName ?? plugin.artifact} service={service} plugin={plugin} />
                ))}
              </ul>
            )}
          </TabsContent>

          <TabsContent value="add" className="flex flex-col gap-3">
            <Search service={service} loader={plugins.data?.loader} version={plugins.data?.gameVersion} />
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  )
}

// --- what is on the server ----------------------------------------------------------------------

function InstalledRow({ service, plugin }: { service: string; plugin: ServicePlugin }) {
  return (
    <li className="flex items-center gap-3 border-b border-border pb-2 last:border-b-0 last:pb-0">
      <Thumbnail url={plugin.iconUrl} alt={plugin.name} />
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm">{plugin.name}</span>
        <span className="truncate text-xs text-muted-foreground">
          {plugin.fileName ?? plugin.filePrefix}
        </span>
      </div>
      {plugin.running ? null : (
        <StatusBadge tone="idle" title="Installs with the next update run.">
          pre-booked
        </StatusBadge>
      )}
      <Link url={plugin.pageUrl} title={plugin.name} />
      {plugin.removable && plugin.artifact ? (
        <RemoveButton service={service} plugin={plugin} artifact={plugin.artifact} />
      ) : null}
    </li>
  )
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
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove {plugin.name}?</DialogTitle>
            <DialogDescription>{removalSentence(plugin)}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
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
          </DialogFooter>
        </DialogContent>
      </Dialog>
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
 * rather than inventing one. A pre-booked plugin: there is no jar and no folder, only the row.
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

      {results.isPending ? (
        <Loading rows={3} />
      ) : results.error ? (
        <Failure error={results.error} onRetry={results.refetch} />
      ) : results.data.hits.length === 0 ? (
        <Empty title="Nothing found" note={`Nothing on ${loader} for ${version}.`} />
      ) : (
        <ul className="flex flex-col gap-2">
          {results.data.hits.map((hit) => (
            <li
              key={hit.projectId}
              className="flex items-center gap-3 border-b border-border pb-2 last:border-b-0 last:pb-0"
            >
              <Thumbnail url={hit.iconUrl} alt={hit.title} />
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-sm">{hit.title}</span>
                <span className="truncate text-xs text-muted-foreground">{hit.description}</span>
                <span className="text-xs text-muted-foreground tnum">{count(hit.downloads)}</span>
              </div>
              <Link url={hit.pageUrl} title={hit.title} />
              <InstallButton hit={hit} install={install} />
            </li>
          ))}
        </ul>
      )}
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
      <StatusBadge tone="ok" title="The network gives this plugin. It cannot be removed.">
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
function Thumbnail({ url, alt }: { url?: string; alt: string }) {
  if (!url) {
    return <div className="size-8 shrink-0 rounded-sm bg-muted" aria-hidden />
  }
  return (
    <img
      src={url}
      alt={alt}
      loading="lazy"
      className="size-8 shrink-0 rounded-sm object-cover"
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
