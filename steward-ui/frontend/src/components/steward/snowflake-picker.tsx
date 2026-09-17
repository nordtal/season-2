import { useMemo, useState } from "react"
import { TriangleAlert } from "lucide-react"

import type { GuildEntry, GuildList } from "@/lib/api"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

/**
 * A Discord id, picked by name instead of typed.
 *
 * **Why this exists.** Every id in the bot's configuration is an eighteen-digit number that a
 * person can read in exactly one place: Discord's own right-click menu, behind a developer-mode
 * switch most people have never turned on. Typing one into a text field is a transcription with no
 * feedback - the wrong id is still a valid snowflake, so nothing refuses it, and the first sign of
 * the mistake is a message appearing in a channel nobody meant. A list of names removes the whole
 * class of mistake.
 *
 * **It always degrades to the field it replaces.** No bot token, an unreachable Discord, a rate
 * limit - any of them and this is the text input again, with the reason underneath it. The ids
 * still work; it is the names that are missing. That is also why an id that is set but is not in
 * the list is kept and shown as itself rather than cleared: a channel the bot cannot see is not a
 * value this page gets to delete.
 *
 * **The open list draws a name and nothing else** (steward/53, applying steward/45's rule to roles
 * and channels): a raw snowflake never appears in a row here, even in a muted corner. Searching is
 * a different question from showing - the filter above the list matches the id as well as the
 * name, because somebody holding an id copied from a log or from Discord's own "Copy ID" has to be
 * able to paste it in and land on the right row without ever being shown what they pasted. The one
 * exception is {@link withUnknown}'s placeholder: an id the guild did not name is the one case
 * where the id *is* the only honest thing to show, and it is drawn to look like exactly that - a
 * missing name, not an ordinary row.
 */
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
  const entries = directory?.available ? directory.entries : []

  // A category (Discord type 4) is not a channel anything here posts in, but it is what tells two
  // channels of the same name apart, so it is kept as a heading rather than dropped.
  const options = useMemo(() => withUnknown(entries, value), [entries, value])
  const [query, setQuery] = useState("")
  // The row that is set now is never filtered out, whatever is typed. Radix reads the closed
  // trigger's text off the mounted item, so hiding the chosen row empties the control the person is
  // looking at - and being told your setting has no value because you typed in a search box is a
  // worse lie than a row that does not match sitting in the list.
  const visible = useMemo(
    () =>
      query.trim()
        ? options.filter((entry) => entry.id === value || matchesQuery(entry, what, query))
        : options,
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
          className="font-mono text-sm"
          onChange={(event) => onChange(event.target.value)}
        />
        {directory?.reason ? (
          <p className="text-xs text-muted-foreground">
            {directory.reason} Paste the id instead: Discord → Settings → Advanced → Developer Mode,
            then right-click the {what} → Copy {what === "role" ? "Role" : "Channel"} ID.
          </p>
        ) : null}
      </div>
    )
  }

  return (
    // The empty option is a real choice and says what choosing it means, because "none" in this
    // interface is never "the default" - it is the feature switched off, and the bot says which.
    <Select
      value={value === "" ? NOTHING : value}
      disabled={disabled}
      onValueChange={(next) => onChange(next === NOTHING ? "" : next)}
    >
      <SelectTrigger id={id} className="font-mono text-sm">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {/* A plain input, not a `Select` item - Radix would otherwise read every keystroke here as
            its own typeahead and jump the highlighted row instead of letting the user type. */}
        <div className="sticky top-0 z-10 -mx-1 -mt-1 mb-1 border-b border-border bg-popover p-1.5">
          <Input
            role="searchbox"
            aria-label={`Search ${what}s`}
            placeholder={`Search ${what}s, or paste an id…`}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => event.stopPropagation()}
            className="h-8 text-sm"
          />
        </div>
        <SelectItem value={NOTHING}>
          <span className="text-muted-foreground">none — this feature is not served</span>
        </SelectItem>
        {visible.map((entry) => {
          const named = entries.some((known) => known.id === entry.id)
          return (
            <SelectItem key={entry.id} value={entry.id}>
              {named ? (
                <span className="font-medium">{label(entry, what)}</span>
              ) : (
                <span className="flex min-w-0 items-center gap-1.5 text-muted-foreground italic">
                  <TriangleAlert aria-hidden className="size-3.5 shrink-0" />
                  <span className="truncate">name unavailable</span>
                  <span className="shrink-0 font-mono text-xs not-italic">{entry.id}</span>
                </span>
              )}
            </SelectItem>
          )
        })}
        {visible.length === 0 ? (
          <p className="px-2 py-3 text-center text-xs text-muted-foreground">No match.</p>
        ) : null}
      </SelectContent>
    </Select>
  )
}

/**
 * Radix refuses an empty string as an item value - it is how it spells "nothing is selected" - so
 * "no channel" needs a sentinel of its own. A `#` cannot begin a snowflake, so it can never collide
 * with a real id.
 */
const NOTHING = "#none"

function label(entry: GuildEntry, what: "role" | "channel"): string {
  if (what === "role") return entry.name
  if (entry.type === 4) return `${entry.name} (category)`
  if (entry.type === 2 || entry.type === 13) return `🔊 ${entry.name}`
  return `# ${entry.name}`
}

/**
 * Whether `entry` matches a typed `query` - by the name the list actually draws, or by the raw
 * snowflake it never draws (steward/53). Searching and showing are different questions: the row
 * never prints an id, but a person holding one from a log or from Discord's own "Copy ID" still
 * has to be able to paste it here and land on the right row.
 */
export function matchesQuery(entry: GuildEntry, what: "role" | "channel", query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return label(entry, what).toLowerCase().includes(needle) || entry.id.includes(needle)
}

/**
 * An id the guild did not list is still offered, as itself.
 *
 * A channel the bot cannot see, or one deleted since it was configured, would otherwise vanish out
 * of the select the moment the page drew it - and a value silently replaced by "none" is the exact
 * failure this component exists to prevent, only faster.
 */
export function withUnknown(entries: GuildEntry[], value: string): GuildEntry[] {
  if (value === "" || entries.some((entry) => entry.id === value)) return entries
  return [{ id: value, name: "unknown — not in this guild, or not visible to the bot", type: null },
    ...entries]
}
