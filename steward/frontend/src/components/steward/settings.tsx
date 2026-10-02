import { ArrowLeftIcon, CaretRightIcon, GearSixIcon, LockIcon, TranslateIcon } from "@phosphor-icons/react"
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { cn } from "cn"

import type { ConfigLocation, MessageBundleLocation } from "@/lib/api"
import { useDirtyFiles } from "@/lib/drafts"
import { useConfigs, useMessageBundles } from "@/lib/queries"
import { onPendingJump, onPendingMessageJump, takePendingJump, takePendingMessageJump } from "@/lib/settings-search"
import { fileTitle, translationsTitle } from "@/lib/words"
import { Failure, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { DraftDot, type FileItem, type Target, useWide } from "@/components/steward/settings-view"
import { ConfigFile } from "@/components/steward/settings-config"
import { BundleFile } from "@/components/steward/settings-messages"

/** The service the network's own settings are published under, the ones every process reads. */
export const NETWORK = "network"

/**
 * The Settings & Translations tab: the service's config files and bundles in one list, the chosen one as a tree.
 *
 * The open file is the page's `?file=`, handed in and reported back rather than kept here.
 */
export function ServiceSettings({
  service,
  file,
  onFile,
}: {
  service: string
  file: string | undefined
  /** `replace` for a choice nobody made, so Back does not return to a list that chose itself. */
  onFile: (file: string | undefined, replace?: boolean) => void
}) {
  const configs = useConfigs()
  const bundles = useMessageBundles()
  const dirty = useDirtyFiles()
  const wide = useWide()
  const [target, setTarget] = useState<Target | null>(null)
  const report = useRef(onFile)
  useLayoutEffect(() => {
    report.current = onFile
  })

  const files = useMemo(
    () => filesOf(service, configs.data ?? [], bundles.data ?? []),
    [service, configs.data, bundles.data],
  )
  const loading = configs.isPending || bundles.isPending
  /** On a wide screen the first readable file is shown when none is chosen, derived rather than written to the URL. */
  const shown = file ?? (wide ? files.find((item) => item.readable)?.id : undefined)
  const selected = files.find((item) => item.id === shown)

  /** A jump from the command palette; each gets a new number, so the same hit twice lands twice. */
  useEffect(() => {
    const land = () => {
      const config = takePendingJump(service)
      if (config) {
        setTarget({ file: config.file, id: config.path, seq: ++jumps })
        report.current(config.file)
      }
      const message = takePendingMessageJump(service)
      if (message) {
        const id = bundleFileId(message.path)
        setTarget({ file: id, id: message.key, language: message.language, seq: ++jumps })
        report.current(id)
      }
    }
    land()
    const stopConfig = onPendingJump(land)
    const stopMessage = onPendingMessageJump(land)
    return () => {
      stopConfig()
      stopMessage()
    }
  }, [service])

  const failure = configs.error ?? bundles.error

  return (
    <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[15rem_minmax(0,1fr)] lg:items-start lg:gap-8">
      <nav
        aria-label="Files"
        className={cn("flex flex-col gap-0.5 lg:sticky lg:top-4", file !== undefined && "max-lg:hidden")}
      >
        {failure ? <Failure error={failure} /> : null}
        {loading ? (
          [0, 1, 2].map((index) => (
            <div key={index} className="flex h-9 items-center gap-2 px-2">
              <SkeletonText className="text-sm" width="medium" />
            </div>
          ))
        ) : files.length === 0 && !failure ? (
          <p className="px-2 text-sm text-muted-foreground">No files.</p>
        ) : (
          files.map((item) => (
            <FileRow
              key={item.id}
              item={item}
              selected={item.id === shown}
              dirty={dirty.includes(item.id)}
              onSelect={() => onFile(item.id)}
            />
          ))
        )}
      </nav>

      <div className={cn("min-w-0", file === undefined && "max-lg:hidden")}>
        {selected ? (
          <>
            <div className="-ml-2 mb-2 flex h-9 items-center gap-1 lg:hidden">
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Back to the files"
                onClick={() => {
                  if (historyEntryIndex() > 0) window.history.back()
                  else onFile(undefined)
                }}
              >
                <ArrowLeftIcon aria-hidden />
              </Button>
              <span className="min-w-0 truncate text-sm font-medium">{selected.label}</span>
            </div>
            {selected.kind === "config" ? (
              <ConfigFile key={selected.id} item={selected} target={target} />
            ) : (
              <BundleFile key={selected.id} item={selected} target={target} />
            )}
          </>
        ) : file !== undefined && !loading ? (
          <p className="text-sm text-muted-foreground">No such file.</p>
        ) : null}
      </div>
    </div>
  )
}

/** Where a bundle is in `?file=`: its path would be a config file's otherwise. */
export function bundleFileId(path: string): string {
  return `bundle:${path}`
}

/** A counter, not state: the number only has to differ from the last one. */
let jumps = 0

/** The router's own position in the browser history, or 0 for an entry it never numbered. */
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

/** The translations first, then the groups of settings. */
function filesOf(service: string, configs: ConfigLocation[], bundles: MessageBundleLocation[]): FileItem[] {
  const settings: FileItem[] = configs
    .filter((file) => file.service === service)
    .map((location) => ({
      kind: "config",
      id: location.path,
      label: fileTitle(location.name),
      readable: location.readable,
      writable: location.writable,
      location,
    }))
  const translations: FileItem[] = bundles
    .filter((bundle) => bundle.service === service)
    .map((location) => ({
      kind: "bundle",
      id: bundleFileId(location.path),
      label: translationsTitle(location),
      readable: true,
      writable: location.writable,
      location,
    }))
  return [...translations.toSorted(byLabel), ...settings.toSorted(byLabel)]
}

function FileRow({
  item,
  selected,
  dirty,
  onSelect,
}: {
  item: FileItem
  selected: boolean
  dirty: boolean
  onSelect: () => void
}) {
  const Icon = item.kind === "config" ? GearSixIcon : TranslateIcon
  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={!item.readable}
      aria-current={selected ? "page" : undefined}
      className={cn(
        "flex h-9 w-full items-center gap-2 rounded-md px-2 text-left text-sm hover:bg-accent disabled:cursor-not-allowed disabled:text-destructive disabled:hover:bg-transparent",
        selected && "bg-accent",
      )}
    >
      <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <span className="min-w-0 flex-1 truncate">{item.label}</span>
      {dirty ? <DraftDot /> : null}
      {!item.readable ? (
        <LockIcon className="size-3.5 shrink-0" aria-label="Not readable" />
      ) : !item.writable ? (
        <LockIcon className="size-3.5 shrink-0 text-muted-foreground" aria-label="Read-only" />
      ) : null}
      <CaretRightIcon className="size-4 shrink-0 text-muted-foreground lg:hidden" aria-hidden />
    </button>
  )
}
