import { ArrowLeftIcon, CaretRightIcon, FolderIcon, GearSixIcon, LockIcon } from "@phosphor-icons/react"
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"
import { cn } from "cn"

import { ApiError, type ConfigLocation, type PluginDescriptor, type PluginFile } from "@/lib/api"
import { setOpened, useDirtyFiles, useOpened } from "@/lib/drafts"
import { useConfigs, useDescriptors, usePluginFiles, useService } from "@/lib/queries"
import { onPendingJump, takePendingJump } from "@/lib/settings-search"
import {
  ancestorsOf,
  pluginFileId,
  pluginFileTree,
  pluginLabel,
  type PluginFileLeaf,
  type TreeBranch,
  type TreeLeaf,
} from "@/lib/settings-tree"
import { fileTitle } from "@/lib/words"
import { Failure, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { DraftDot, type FileItem, NodeList, type Target, useWide } from "@/components/steward/settings-view"
import { ConfigFile } from "@/components/steward/settings-config"
import { PluginFileEditor } from "@/components/steward/settings-plugin-file"
import { Thumbnail } from "@/components/steward/plugins"
import { TERM_ICON } from "@/components/steward/term-icons"
import { t } from "@/lib/texts"
import { StewardMark } from "@/app/steward-mark"

/** The service under which the network's own settings are published. Every process reads them. */
export const NETWORK = "network"

/**
 * The open file is the page's `?file=`. The page passes it in and receives changes through a callback.
 */
export function ServiceSettings({
  service,
  file,
  onFile,
}: {
  service: string
  file: string | undefined
  /**
   * `replace` is set for a choice that nobody made, so Back does not return to a list that chose itself.
   */
  onFile: (file: string | undefined, replace?: boolean) => void
}) {
  const configs = useConfigs()
  /**
   * Without the descriptors a group falls back to the form that its schema draws. That is all a failed read costs.
   */
  const descriptors = useDescriptors()
  const dirty = useDirtyFiles()
  const wide = useWide()
  const [target, setTarget] = useState<Target | null>(null)
  const report = useRef(onFile)
  useLayoutEffect(() => {
    report.current = onFile
  })

  const files = useMemo(
    () => filesOf(service, configs.data ?? [], descriptors.data ?? []),
    [service, configs.data, descriptors.data],
  )
  const ownService = useService(service)
  const pluginFiles = usePluginFiles(service, ownService.data?.hasPlugins === true)
  const plugins = useMemo(() => pluginFileTree(pluginFiles.data?.files ?? []), [pluginFiles.data])
  const pluginLeaves = useMemo(() => (pluginFiles.data?.files ?? []).map(pluginLeaf), [pluginFiles.data])
  const loading = configs.isPending
  /**
   * On a wide screen the first readable file shows when none is chosen. The component derives it and does not write it to the URL.
   */
  const shown =
    file ??
    (wide
      ? (files.find((item) => item.readable)?.id ?? pluginLeaves.find((leaf) => leaf.file.readable)?.id)
      : undefined)
  const selected = files.find((item) => item.id === shown)
  const selectedPlugin = pluginLeaves.find((leaf) => leaf.id === shown)
  const openedPlugins = useOpened(pluginsKey(service))
  const chain = useMemo(() => (shown ? (ancestorsOf(plugins, shown) ?? []) : []), [plugins, shown])
  /** A jump to a plugin file opens the rows above it, even ones closed by hand. */
  useEffect(() => {
    for (const branch of chain) setOpened(pluginsKey(service), branch, true)
  }, [chain, service])
  const dirtyIds = useMemo(() => new Set(dirty), [dirty])

  /**
   * A jump from the command palette. Each jump gets a new number, so the same hit twice lands twice.
   */
  useEffect(() => {
    const land = () => {
      const config = takePendingJump(service)
      if (config) {
        setTarget({ file: config.file, id: config.path, seq: ++jumps })
        report.current(config.file)
      }
    }
    land()
    return onPendingJump(land)
  }, [service])

  const failure = configs.error ?? (isMissing(pluginFiles.error) ? null : pluginFiles.error)
  const isOpen = (id: string) => openedPlugins[id] ?? chain.includes(id)

  return (
    <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[15rem_minmax(0,1fr)] lg:items-start lg:gap-8">
      <nav
        aria-label={t("steward.settings.files")}
        className={cn(
          "flex flex-col gap-0.5 lg:sticky lg:top-[calc(var(--content-top,0px)+1rem)]",
          file !== undefined && "max-lg:hidden",
        )}
      >
        {failure ? <Failure error={failure} /> : null}
        {loading ? (
          [0, 1, 2].map((index) => (
            <div key={index} className="flex h-9 items-center gap-2 px-2">
              <SkeletonText className="text-sm" width="medium" />
            </div>
          ))
        ) : files.length === 0 && plugins.length === 0 && !failure ? (
          <p className="px-2 text-sm text-muted-foreground">{t("steward.settings.no-files")}</p>
        ) : (
          files.map((item) => (
            <FileRow
              key={item.id}
              item={item}
              selected={item.id === shown}
              dirty={dirty.includes(item.id)}
              icon={<StewardMark className="size-4 shrink-0" />}
              onSelect={() => onFile(item.id)}
            />
          ))
        )}
        {plugins.length > 0 ? (
          <>
            {files.length > 0 ? <hr className="mx-2 my-1.5 border-border" /> : null}
            <NodeList<PluginFileLeaf>
              nodes={plugins}
              isOpen={isOpen}
              onToggle={(id) => setOpened(pluginsKey(service), id, !isOpen(id))}
              draftIds={dirtyIds}
              highlight={null}
              list
              branchIcon={branchIcon}
              renderLeaf={(leaf) => (
                <FileRow
                  item={{ label: leaf.value.label, ...leaf.value.file }}
                  selected={leaf.id === shown}
                  dirty={dirtyIds.has(leaf.id)}
                  icon={
                    leaf.value.alone ? (
                      <PluginIcon file={leaf.value.file} />
                    ) : (
                      <GearSixIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    )
                  }
                  onSelect={() => onFile(leaf.id)}
                />
              )}
            />
          </>
        ) : null}
      </nav>

      <div className={cn("min-w-0", file === undefined && "max-lg:hidden")}>
        {selected ? (
          <>
            <BackRow label={selected.label} onFile={onFile} />
            <ConfigFile key={selected.id} item={selected} target={target} />
          </>
        ) : selectedPlugin ? (
          <>
            <BackRow
              label={
                <>
                  {pluginLabel(selectedPlugin.file)}
                  <span className="truncate font-normal text-muted-foreground">{selectedPlugin.file.path}</span>
                </>
              }
              onFile={onFile}
            />
            <PluginFileEditor
              key={selectedPlugin.id}
              id={selectedPlugin.id}
              file={selectedPlugin.file}
              label={selectedPlugin.label}
              target={target}
            />
          </>
        ) : file !== undefined && !loading && !ownService.isPending && !pluginFiles.isLoading ? (
          <p className="text-sm text-muted-foreground">{t("steward.settings.no-such-file")}</p>
        ) : null}
      </div>
    </div>
  )
}

/** On a phone the list and the open file are separate screens; this leads back to the list. */
function BackRow({ label, onFile }: { label: ReactNode; onFile: (file: string | undefined) => void }) {
  return (
    <div className="-ml-2 mb-2 flex h-9 items-center gap-1 lg:hidden">
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={t("steward.settings.back-to-files")}
        onClick={() => {
          if (historyEntryIndex() > 0) window.history.back()
          else onFile(undefined)
        }}
      >
        <ArrowLeftIcon aria-hidden />
      </Button>
      <span className="flex min-w-0 items-baseline gap-2 truncate text-sm font-medium">{label}</span>
    </div>
  )
}

/** Where the plugin rows' open state is kept, apart from any file's own branches. */
function pluginsKey(service: string): string {
  return `plugins:${service}`
}

/** A 404 means the service has no plugins folder, which is no failure. */
function isMissing(error: Error | null): boolean {
  return error instanceof ApiError && error.status === 404
}

/** A plugin file by its id, named by its plugin and its path for the editor and its save's toast. */
function pluginLeaf(file: PluginFile): { id: string; label: string; file: PluginFile } {
  return { id: pluginFileId(file), label: `${pluginLabel(file)} ${file.path}`, file }
}

function PluginIcon({ file }: { file: PluginFile }) {
  if (file.iconUrl) return <Thumbnail url={file.iconUrl} alt="" className="size-4 rounded-sm" />
  if (file.name === undefined) return <FolderIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
  return <TERM_ICON.plugins className="size-4 shrink-0 text-muted-foreground" aria-hidden />
}

/** A plugin's row shows the plugin, a folder's row a folder. */
function branchIcon(branch: TreeBranch<PluginFileLeaf>): ReactNode {
  const first = firstLeaf(branch)
  if (first && branch.id === `plugin:${first.value.file.plugin}`) return <PluginIcon file={first.value.file} />
  return <FolderIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
}

function firstLeaf(branch: TreeBranch<PluginFileLeaf>): TreeLeaf<PluginFileLeaf> | undefined {
  for (const child of branch.children) {
    const leaf = child.kind === "leaf" ? child : firstLeaf(child)
    if (leaf) return leaf
  }
  return undefined
}

/** A counter and not state, because the number only has to differ from the last one. */
let jumps = 0

function historyEntryIndex(): number {
  const state: unknown = window.history.state
  const key = "__TSR_index"
  if (typeof state !== "object" || state === null || !(key in state)) return 0
  const index = state[key]
  return typeof index === "number" ? index : 0
}

function byLabel(a: FileItem, b: FileItem): number {
  return a.label.localeCompare(b.label)
}

function filesOf(service: string, configs: ConfigLocation[], descriptors: PluginDescriptor[]): FileItem[] {
  const settings: FileItem[] = configs
    .filter((file) => file.service === service)
    .map((location) => ({
      id: location.path,
      label: fileTitle(location.name),
      readable: location.readable,
      writable: location.writable,
      location,
      editor: descriptorOf(descriptors, location.service)?.editors[location.name],
    }))
  return settings.toSorted(byLabel)
}

function descriptorOf(descriptors: PluginDescriptor[], id: string): PluginDescriptor | undefined {
  return descriptors.find((descriptor) => descriptor.id === id)
}

function FileRow({
  item,
  selected,
  dirty,
  icon,
  onSelect,
}: {
  item: Pick<FileItem, "label" | "readable" | "writable">
  selected: boolean
  dirty: boolean
  icon: ReactNode
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={!item.readable}
      aria-current={selected ? "page" : undefined}
      className={cn(
        "flex h-9 w-full items-center gap-2 rounded-md px-2 text-left text-sm pointer-coarse:min-h-control hover:bg-accent disabled:cursor-not-allowed disabled:text-destructive disabled:hover:bg-transparent",
        selected && "bg-accent",
      )}
    >
      {icon}
      <span className="min-w-0 flex-1 truncate">{item.label}</span>
      {dirty ? <DraftDot /> : null}
      {!item.readable ? (
        <LockIcon className="size-3.5 shrink-0" aria-label={t("steward.settings.not-readable")} />
      ) : !item.writable ? (
        <LockIcon className="size-3.5 shrink-0 text-muted-foreground" aria-label={t("steward.settings.read-only")} />
      ) : null}
      <CaretRightIcon className="size-4 shrink-0 text-muted-foreground lg:hidden" aria-hidden />
    </button>
  )
}
