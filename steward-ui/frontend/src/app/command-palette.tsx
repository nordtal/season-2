import * as React from "react"
import { useNavigate } from "@tanstack/react-router"
import { History } from "lucide-react"

import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
  CommandShortcut,
} from "@/components/ui/command"
import { NAVIGATION } from "@/app/navigation"
import { RUN_KIND_SEARCH_TERMS } from "@/app/run-search-terms"
import type { Run } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import { useRuns } from "@/lib/queries"
import { RUN_KIND, RUN_STATUS } from "@/components/steward/status"

/**
 * A run's own search text.
 *
 * steward/52: a run has four things a person would search it by - its number, its kind, its
 * outcome and a time - and none of them is a page title, which is what the palette searched
 * before this. `dateTime` rather than `relative` for the time: `relative` reads differently on
 * every render ("3 minutes ago" becomes "4 minutes ago"), which would make the same run match a
 * different typed time from one moment to the next. `RUN_KIND_SEARCH_TERMS` adds the words a
 * person would type that are not the kind's own name - German among them, because Till does not
 * always type English. See that file for why one of those words is German rather than English.
 */
function runSearchValue(run: Run): string {
  return [
    `run #${run.id}`,
    RUN_KIND[run.kind] ?? run.kind,
    RUN_STATUS[run.status] ?? run.status,
    dateTime(run.requested),
    ...(RUN_KIND_SEARCH_TERMS[run.kind] ?? []),
  ].join(" ")
}

/**
 * Ctrl+K, and ⌘K on a Mac - both are listened for, always, so the label the interface prints is
 * a courtesy and never a condition. Every route in the interface is in here, including the parameterised
 * ones, which appear with a representative parameter - the point of the palette is that a place
 * you know the name of is one keystroke away, and "Run" is a name somebody knows.
 *
 * Runs themselves are the other half, added for steward/52: `useRuns` is only enabled while the
 * dialog is open, so a palette nobody has opened costs nothing beyond the one poll a page that is
 * already open may be running anyway.
 */
export function CommandPalette() {
  const [open, setOpen] = React.useState(false)
  const navigate = useNavigate()
  const runs = useRuns(20, open).data ?? []
  // The listener is registered once, so it would otherwise read the `open` of the render it was
  // created in - which is always false.
  const openRef = React.useRef(open)
  openRef.current = open

  React.useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== "k") return
      if (!event.metaKey && !event.ctrlKey) return
      // Let the browser keep its own Ctrl+K when the user is typing into something. The comment
      // said so and the code did the opposite: every Ctrl+K in a console line or a config field
      // was swallowed and opened the search instead. The palette's own input is the exception -
      // there the shortcut is how you close it again.
      if (!openRef.current && isEditable(event.target)) return
      event.preventDefault()
      setOpen((previous) => !previous)
    }
    document.addEventListener("keydown", onKeyDown)
    return () => document.removeEventListener("keydown", onKeyDown)
  }, [])

  return (
    <CommandDialog
      open={open}
      onOpenChange={setOpen}
      title="Search"
      description="Jump to a page"
      className="top-[20%] translate-y-0"
    >
      <CommandInput placeholder="Search pages…" />
      <CommandList className="max-h-[22rem]">
        <CommandEmpty>Nothing found.</CommandEmpty>
        {NAVIGATION.map((group, index) => (
          <React.Fragment key={group.id}>
            {index > 0 ? <CommandSeparator /> : null}
            <CommandGroup heading={group.label}>
              {group.entries.map((entry) => {
                const Icon = entry.icon ?? group.icon
                return (
                  <CommandItem
                    key={entry.id}
                    value={[entry.label, group.label, entry.note, ...(entry.keywords ?? [])]
                      .filter(Boolean)
                      .join(" ")}
                    onSelect={() => {
                      setOpen(false)
                      void navigate({ to: entry.to, params: entry.params as never })
                    }}
                    className="min-h-control gap-2.5"
                  >
                    <Icon aria-hidden className="text-muted-foreground" />
                    <span className="truncate">{entry.label}</span>
                    <CommandShortcut className="truncate text-muted-foreground/70">
                      {entry.params
                        ? Object.values(entry.params).join(" · ")
                        : null}
                    </CommandShortcut>
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </React.Fragment>
        ))}
        {runs.length > 0 ? (
          <>
            <CommandSeparator />
            <CommandGroup heading="Runs">
              {runs.map((run) => (
                <CommandItem
                  key={`run-${run.id}`}
                  value={runSearchValue(run)}
                  onSelect={() => {
                    setOpen(false)
                    void navigate({
                      to: "/operations/runs/$id",
                      params: { id: String(run.id) },
                    })
                  }}
                  className="min-h-control gap-2.5"
                >
                  <History aria-hidden className="text-muted-foreground" />
                  <span className="truncate">
                    Run #{run.id} · {RUN_KIND[run.kind] ?? run.kind}
                  </span>
                  <CommandShortcut className="truncate text-muted-foreground/70">
                    {RUN_STATUS[run.status] ?? run.status} · {relative(run.requested)}
                  </CommandShortcut>
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        ) : null}
      </CommandList>
    </CommandDialog>
  )
}

/** Whether the key went to something somebody is typing in. */
function isEditable(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)
}
