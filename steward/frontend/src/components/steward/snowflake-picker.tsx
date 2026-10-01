import { WarningIcon } from "@phosphor-icons/react"
import { useMemo, useState } from "react"

import type { GuildEntry, GuildList } from "@/lib/api"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/** A stable identity for "no entries", so a memo keyed on it does not recompute every render. */
const NO_ENTRIES: GuildEntry[] = []

/** A Discord id picked by name, falling back to a plain id field with the reason when the guild cannot be listed. */
export function SnowflakePicker({
  id,
  value,
  directory,
  what,
  disabled,
  onChange,
}: {
  id: string
  value: string
  directory: GuildList | undefined
  /** What is being picked, for the empty option and the fallback hint. */
  what: "role" | "channel"
  disabled: boolean
  onChange: (value: string) => void
}) {
  const entries = directory?.available ? directory.entries : NO_ENTRIES

  /** Categories are kept as headings, since they tell two channels of the same name apart. */
  const options = useMemo(() => withUnknown(entries, value), [entries, value])
  const [query, setQuery] = useState("")
  /** The row set now is never filtered out, since Radix reads the closed trigger's text off it. */
  const visible = useMemo(
    () => (query.trim() ? options.filter((entry) => entry.id === value || matchesQuery(entry, what, query)) : options),
    [options, query, value, what],
  )

  if (!directory?.available) {
    return (
      <div className="flex flex-col gap-1.5">
        <Input
          id={id}
          disabled={disabled}
          value={value}
          spellCheck={false}
          className="font-mono text-sm max-md:text-base"
          onChange={(event) => onChange(event.target.value)}
        />
        {directory?.reason ? (
          <p className="text-xs text-muted-foreground">
            {directory.reason} Paste the id instead: Discord → Settings → Advanced → Developer Mode, then right-click
            the {what} → Copy {what === "role" ? "Role" : "Channel"} ID.
          </p>
        ) : null}
      </div>
    )
  }

  return (
    /** The empty option says what choosing it means: the feature switched off. */
    <Select
      value={value === "" ? NOTHING : value}
      disabled={disabled}
      onValueChange={(next) => onChange(next === NOTHING ? "" : next)}
    >
      <SelectTrigger id={id} className="font-mono text-sm">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {/* A plain input, not a `Select` item, or Radix would read each keystroke as typeahead. */}
        <div className="sticky top-0 z-10 -mx-1 -mt-1 mb-1 border-b border-border bg-popover p-1.5">
          <Input
            role="searchbox"
            aria-label={`Search ${what}s`}
            placeholder={`Search ${what}s, or paste an id…`}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => event.stopPropagation()}
            className="h-8 text-sm max-md:text-base"
          />
        </div>
        <SelectItem value={NOTHING}>
          <span className="text-muted-foreground">none {"\u2014"} this feature is not served</span>
        </SelectItem>
        {visible.map((entry) => {
          const named = entries.some((known) => known.id === entry.id)
          return (
            <SelectItem key={entry.id} value={entry.id}>
              {named ? (
                <span className="font-medium">{label(entry, what)}</span>
              ) : (
                <span className="flex min-w-0 items-center gap-1.5 text-muted-foreground italic">
                  <WarningIcon aria-hidden className="size-3.5 shrink-0" />
                  <span className="truncate">name unavailable</span>
                  <span className="shrink-0 font-mono text-xs not-italic">{entry.id}</span>
                </span>
              )}
            </SelectItem>
          )
        })}
        {visible.length === 0 ? <p className="px-2 py-3 text-center text-xs text-muted-foreground">No match.</p> : null}
      </SelectContent>
    </Select>
  )
}

/** Radix refuses "" as an item value, and `#` cannot begin a snowflake. */
const NOTHING = "#none"

function label(entry: GuildEntry, what: "role" | "channel"): string {
  if (what === "role") return entry.name
  if (entry.type === 4) return `${entry.name} (category)`
  if (entry.type === 2 || entry.type === 13) return `🔊 ${entry.name}`
  return `# ${entry.name}`
}

/** Whether `entry` matches `query`, by the name drawn or by the id it never draws, so a pasted id lands. */
export function matchesQuery(entry: GuildEntry, what: "role" | "channel", query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return label(entry, what).toLowerCase().includes(needle) || entry.id.includes(needle)
}

/** An id the guild did not list, still offered as itself rather than silently replaced by none. */
export function withUnknown(entries: GuildEntry[], value: string): GuildEntry[] {
  if (value === "" || entries.some((entry) => entry.id === value)) return entries
  return [{ id: value, name: "unknown \u2014 not in this guild, or not visible to the bot", type: null }, ...entries]
}
