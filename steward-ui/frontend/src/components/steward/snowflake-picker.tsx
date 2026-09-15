import { useMemo } from "react"

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
        <SelectItem value={NOTHING}>
          <span className="text-muted-foreground">none — this feature is not served</span>
        </SelectItem>
        {options.map((entry) => (
          <SelectItem key={entry.id} value={entry.id}>
            <span className="font-medium">{label(entry, what)}</span>
            <span className="ml-2 font-mono text-xs text-muted-foreground">{entry.id}</span>
          </SelectItem>
        ))}
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
