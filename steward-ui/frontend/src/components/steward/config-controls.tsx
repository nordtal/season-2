import type { ConfigChoices, ConfigEntry, GuildList } from "@/lib/api"
import { ColourControl } from "@/components/steward/colour-control"
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

/**
 * The plain-text file name Till asked for, instead of `nordtal-smp/config.yml` verbatim
 * (steward/56) - mechanical, the same way `Labels.of` on the backend turns a YAML key into a
 * label: strip the extension, split on the characters a path uses to separate words, lower-case
 * them, capitalise the first letter of the result. The path is no longer shown under it
 * (season-2-ops/130): the words of the path are already in the name this builds, and a second
 * monospace line made every row in the list two lines tall. Where the path itself is what matters -
 * an error about a file that could not be parsed - it is named there, not in the browsing list.
 *
 * It lives here rather than in `configuration.tsx`, the same reason `discordId` does (steward/57):
 * `config-search.tsx`'s global and per-service search results (steward/58) need it too, and a
 * third file importing it from `configuration.tsx` while `configuration.tsx` imports the search
 * box back would be a cycle for no reason.
 */
export function humanFileName(name: string): string {
  const withoutExtension = name.replace(/\.[a-z0-9]+$/i, "")
  const words = withoutExtension.split(/[-_./]+/).filter(Boolean)
  if (words.length === 0) return name
  const joined = words.map((word) => word.toLowerCase()).join(" ")
  return joined.charAt(0).toUpperCase() + joined.slice(1)
}

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
 * `hunger-games-channel`, `announcement-channel`. A field inside a repeatable card (steward/57)
 * carries its own bare key - `role`, `contribution-channel` - rather than a path prefixed with the
 * section it lives in, so this still matches it correctly without knowing it is inside a card at
 * all.
 *
 * `guild-id` matches none of them, and that is the intended answer rather than an oversight: the
 * guild is what the list is READ FROM, so offering to pick it out of itself is circular and would
 * draw an empty select on the one field that always has to be typed. It is asserted in the tests
 * so a later rule - anything keyed on `-id`, say - cannot quietly acquire it.
 *
 * **`status-channel` is excluded on purpose (steward/61), where it used to match** - it ends in
 * `-channel` like every real snowflake field, but `AccessSpec.LanguageSpec#statusChannel`'s own
 * `@Comment` says it is stored "as a channel NAME - the bot renames it, it never posts in it", not
 * an id at all. `SnowflakePicker` writes back `entry.id`, an eighteen-digit snowflake, so wiring it
 * to this key would silently replace a channel's plain-text name with a number the bot then
 * searches for and never finds. Found while building the language cards steward/61 asked for -
 * every field they draw goes through this same function, and this is the one of the six that is
 * not a snowflake despite its name. Left as plain text, which is what the value actually is.
 */
export function discordId(entry: ConfigEntry): "role" | "channel" | null {
  if (entry.kind !== "SCALAR" || !entry.editable || entry.secret) return null
  const key = entry.key
  const path = entry.path
  if (key === "status-channel") return null
  if (key === "role" || key.endsWith("-role") || path.startsWith("roles.")) return "role"
  if (key === "channel" || key.endsWith("-channel") || path.startsWith("channels.")) return "channel"
  return null
}

/**
 * Whether an empty channel field is a problem worth calling out, rather than an ordinary blank
 * (steward/61's "a language missing a channel is visibly incomplete, not merely empty").
 *
 * This is generic on purpose - `RepeatableCards` calls it for every field of every card, `languages`
 * included, without knowing it is looking at a language. Two things decide it, both from the schema
 * rather than from a value: {@link discordId} says the field is a channel at all, and the schema's
 * own explanation says whether it is optional. Every field this project marks optional says so in
 * the same word, in the sentence jcore copies verbatim from the `@Comment` -
 * `AccessSpec.LanguageSpec#statusChannel` and `#announcementChannel` both start theirs with
 * "OPTIONAL" - so a field is required unless its own explanation says otherwise. That is a real
 * heuristic, not a schema fact (a future field marked optional in different words would slip past
 * it), and it is the same trade `colourValue` above already makes for the same reason: nothing in
 * `SchemaNode` carries a required/optional flag to read instead.
 */
export function isRequiredChannel(entry: ConfigEntry): boolean {
  if (discordId(entry) !== "channel") return false
  return !(explanationOf(entry) ?? "").includes("OPTIONAL")
}

const HEX_COLOUR = /^#[0-9a-f]{6}$/i

/**
 * Whether a scalar's stored value looks like a colour, `#rrggbb` (steward/63).
 *
 * **Why a heuristic at all.** The ticket's first choice was a "colour" kind in the schema, the same
 * place steward/54 already writes a key's type and its choices - the picker would then follow from
 * the schema like every other control here, instead of being a special case keyed on what a value
 * happens to look like. That needs a new annotation in jcore, a separate repository being changed by
 * someone else tonight for a different reason, so it stays open rather than done: steward/54 is where
 * it belongs, and the day it lands this whole function is deleted in favour of `entry.kind ===
 * "COLOUR"` or whatever that ticket calls it. Until then the ticket's own documented fallback
 * applies - the interface recognises, from the shape of the value alone, that it looks like
 * `#rrggbb`, and shows the picker - and it lives in the same drawer as `discordId` above and the
 * secret heuristic in steward/55: it
 * works until the day it is wrong. That day is an ORDINARY TEXT setting whose value happens to start
 * with "#" and is followed by exactly six hex digits - a literal colour string typed into a field
 * that is not one, say. Nothing shipped as of 2026-09-16 (season-2-ingame/22's `colours.yml`, four of
 * them) looks like that.
 *
 * **It reads `entry.value` - the value the FILE holds - never the live keystroke.** `discordId`
 * above decides on the key rather than the value because the value is what is still missing; here
 * the reasoning runs the other way but lands on the same rule: deciding on whatever is being typed
 * right now would flip the control away from the picker the moment somebody selects the text to
 * retype it, which is the worst possible time to lose it. An empty `entry.value` therefore never
 * matches - it is not "not a colour", it is "unknown", but nothing in one entry says otherwise, and a
 * colour setting saved blank falls back to being an ordinary field until a hex value is typed into it
 * again. That is the honest cost of deciding by value instead of by a schema kind, and it is why the
 * schema route stays open above rather than being called unnecessary.
 */
export function colourValue(entry: ConfigEntry): string | null {
  if (entry.kind !== "SCALAR" || !entry.editable || entry.secret) return null
  const value = entry.value ?? ""
  return HEX_COLOUR.test(value) ? value : null
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
 * card (steward/57) - a secret, a schema's choices, a Discord id, a colour (steward/63), a boolean
 * or plain text, in that order of precedence.
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

  // Decided on `entry.value`, not on `value` (the live draft) - see `colourValue`'s own comment for
  // why. `value` (which may be mid-edit, or blank while somebody retypes it) is still what gets
  // shown and typed into the control once it is chosen.
  if (colourValue(entry) !== null) {
    return <ColourControl id={id} value={value} disabled={disabled} onChange={onChange} />
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
