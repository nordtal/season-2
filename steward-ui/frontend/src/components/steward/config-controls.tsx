import type { ConfigChoices, ConfigEntry, GuildList } from "@/lib/api"
import { SnowflakePicker } from "@/components/steward/snowflake-picker"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"

/**
 * The single-key rendering `configuration.tsx` and `repeatable-cards.tsx` (steward/57) both need.
 *
 * It lives here, apart from both, so that neither has to import the other: `repeatable-cards.tsx`
 * draws one of these per field inside a card, `configuration.tsx` draws one per top-level key, and a
 * component that needed both directions would be a cycle for no reason - the rendering of one key
 * never depended on whether it happened to sit inside a card.
 */

/** The short text under a label - the schema's own words, or the mechanical comment block. */
export function explanationOf(entry: ConfigEntry): string | null {
  if (entry.noExplanationNeeded) return null
  if (entry.explanation) return entry.explanation
  if (entry.comments.length > 0) return entry.comments.join("\n").trim()
  return null
}

/**
 * Which keys hold a Discord id, and whether it is a role or a channel.
 *
 * It is decided on the KEY, not on the value, because the whole point is to help with a key that is
 * still empty - a value-shaped test would offer the picker only once somebody had already typed the
 * thing they needed help typing. The names are the ones jcore writes: `roles.admin`,
 * `channels.admin`, and on each language entry `role`, `contribution-channel`, `link-channel`,
 * `hunger-games-channel`, `status-channel`, `announcement-channel`. A field inside a repeatable
 * card (steward/57) carries its own bare key - `role`, `contribution-channel` - rather than a path
 * prefixed with the section it lives in, so this still matches it correctly without knowing it is
 * inside a card at all.
 *
 * `guild-id` matches none of them, and that is the intended answer rather than an oversight: the
 * guild is what the list is READ FROM, so offering to pick it out of itself is circular and would
 * draw an empty select on the one field that always has to be typed. It is asserted in the tests
 * so a later rule - anything keyed on `-id`, say - cannot quietly acquire it.
 */
export function discordId(entry: ConfigEntry): "role" | "channel" | null {
  if (entry.kind !== "SCALAR" || !entry.editable || entry.secret) return null
  const key = entry.key
  const path = entry.path
  if (key === "role" || key.endsWith("-role") || path.startsWith("roles.")) return "role"
  if (key === "channel" || key.endsWith("-channel") || path.startsWith("channels.")) return "channel"
  return null
}

/**
 * A schema's allowed (or suggested) values (steward/55, steward/56).
 *
 * `strict` is the whole of the difference: a closed list is a select and nothing else, because
 * anything else it could hold is not a valid save. A suggestion is the same select beside a
 * free-text field that still takes anything - so the common case is a click and the uncommon one
 * is still just typing, the way it always was.
 */
function ChoicesControl({
  id,
  value,
  choices,
  disabled,
  onChange,
}: {
  id: string
  value: string
  choices: ConfigChoices
  disabled: boolean
  onChange: (value: string) => void
}) {
  // Radix refuses an item with an empty value, and a value the schema did not list is a normal
  // state here - typed by hand before this shipped, or (when not strict) simply a suggestion not
  // taken. Passing it through as "" leaves the select showing its placeholder rather than a value
  // it does not have.
  const known = choices.values.includes(value)
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Select value={known ? value : ""} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={choices.strict ? id : undefined} className="min-w-48">
          <SelectValue placeholder={choices.strict ? "choose one" : "choose a suggestion"} />
        </SelectTrigger>
        <SelectContent>
          {choices.values.map((option) => (
            <SelectItem key={option} value={option}>
              {option}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {choices.strict ? null : (
        <Input
          id={id}
          aria-label="Free text"
          disabled={disabled}
          value={value}
          spellCheck={false}
          className="min-w-40 flex-1 font-mono text-sm"
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </div>
  )
}

/**
 * One scalar key, drawn the same way whether it sits at the top of a file or inside a repeatable
 * card (steward/57) - a secret, a schema's choices, a Discord id, a boolean or plain text, in that
 * order of precedence.
 *
 * `edited` is the caller's own dirty flag - "has anything been typed here since the value this
 * control started from" - and it decides exactly one thing: whether an empty secret reads as
 * untouched or as a deliberate deletion. It cannot be derived from `value` alone, because an empty
 * string is what an untouched secret already looks like.
 */
export function ScalarControl({
  id,
  entry,
  value,
  edited,
  disabled,
  roles,
  channels,
  onChange,
}: {
  id: string
  entry: ConfigEntry
  value: string
  edited: boolean
  disabled: boolean
  roles: GuildList | undefined
  channels: GuildList | undefined
  onChange: (value: string) => void
}) {
  if (entry.secret) {
    return (
      <div className="flex flex-col gap-1.5">
        <Input
          id={id}
          type="password"
          autoComplete="off"
          disabled={disabled}
          value={value}
          placeholder={entry.filled ? "set - type a new one to replace it" : "empty"}
          onChange={(event) => onChange(event.target.value)}
        />
        <p className="text-sm text-muted-foreground">
          {edited && value === ""
            ? "Saving it empty deletes this secret from the file."
            : "The stored value is never sent to the browser. It can be overwritten, not read back."}
        </p>
      </div>
    )
  }

  // A schema's allowed values (steward/55, steward/56) win over the Discord picker below: they are
  // the more specific of the two, being data this particular key actually declared rather than a
  // guess drawn from its name.
  if (entry.choices) {
    return (
      <ChoicesControl
        id={id}
        value={value}
        choices={entry.choices}
        disabled={disabled}
        onChange={onChange}
      />
    )
  }

  const discord = discordId(entry)
  if (discord) {
    return (
      <SnowflakePicker
        id={id}
        value={value}
        directory={discord === "role" ? roles : channels}
        what={discord}
        disabled={disabled}
        onChange={onChange}
      />
    )
  }

  if (entry.type === "BOOLEAN") {
    return (
      <div className="flex items-center gap-3">
        <Switch
          id={id}
          disabled={disabled}
          checked={value === "true"}
          onCheckedChange={(on) => onChange(on ? "true" : "false")}
        />
        <span className="text-sm text-muted-foreground">{value === "true" ? "on" : "off"}</span>
      </div>
    )
  }

  // A value that already spans lines keeps a box it fits in. Typing a newline into the single-line
  // field is allowed too - the backend turns it into a block scalar - but nobody would find that.
  if (value.includes("\n")) {
    return (
      <Textarea
        id={id}
        rows={Math.min(16, value.split("\n").length + 1)}
        disabled={disabled}
        value={value}
        spellCheck={false}
        className="font-mono text-sm"
        onChange={(event) => onChange(event.target.value)}
      />
    )
  }

  return (
    <Input
      id={id}
      disabled={disabled}
      value={value}
      inputMode={entry.type === "INTEGER" || entry.type === "DECIMAL" ? "decimal" : undefined}
      spellCheck={false}
      className="font-mono text-sm"
      onChange={(event) => onChange(event.target.value)}
    />
  )
}
