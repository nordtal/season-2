import * as React from "react"
import { useNavigate } from "@tanstack/react-router"

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

/**
 * Ctrl+K, and ⌘K on a Mac - both are listened for, always, so the label the interface prints is
 * a courtesy and never a condition. Every route in the interface is in here, including the parameterised
 * ones, which appear with a representative parameter - the point of the palette is that a place
 * you know the name of is one keystroke away, and "Run" is a name somebody knows.
 */
export function CommandPalette() {
  const [open, setOpen] = React.useState(false)
  const navigate = useNavigate()
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
