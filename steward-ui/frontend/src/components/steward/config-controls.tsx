import { PlusIcon, TrashIcon } from "@phosphor-icons/react"
import type { ConfigChoices, ConfigEntry, GuildList } from "@/lib/api"
import { ColourControl } from "@/components/steward/colour-control"
import { SnowflakePicker } from "@/components/steward/snowflake-picker"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { fileTitle } from "@/lib/words"

/** One scalar key's control, shared by `configuration.tsx` and `repeatable-cards.tsx` so neither imports the other. */

/**
 * The plain-text name of a config file, built like `Labels.of`: no extension, words split, Capital Case.
 *
 * Kept here so `config-search.tsx` can import it without a cycle.
 */
export function humanFileName(name: string): string {
  return fileTitle(name)
}

/** The short text under a label: the schema's words, or the file's own comment block. */
export function explanationOf(entry: ConfigEntry): string | null {
  if (entry.noExplanationNeeded) return null
  if (entry.explanation) return entry.explanation
  if (entry.comments.length > 0) return entry.comments.join("\n").trim()
  return null
}

/**
 * Which keys hold a Discord id, and whether a role or a channel, decided on the key so an empty field still helps.
 *
 * `guild-id` matches nothing, and `status-channel` stores a channel name, not an id.
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
 * Whether an empty channel field is a problem, since a language missing a channel is incomplete.
 *
 * A channel is required unless its schema explanation starts with "OPTIONAL".
 */
export function isRequiredChannel(entry: ConfigEntry): boolean {
  if (discordId(entry) !== "channel") return false
  return !(explanationOf(entry) ?? "").includes("OPTIONAL")
}

const HEX_COLOUR = /^#[0-9a-f]{6}$/i

/**
 * The colour a scalar's stored value holds, `#rrggbb`, until the schema gains a colour kind.
 *
 * Reads the file's value, never the draft, so selecting the text to retype it keeps the picker.
 */
export function colourValue(entry: ConfigEntry): string | null {
  if (entry.kind !== "SCALAR" || !entry.editable || entry.secret) return null
  const value = entry.value ?? ""
  return HEX_COLOUR.test(value) ? value : null
}

/** A schema's choices: a strict list is a select only, a suggestion is a select beside free text. */
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
  /** A value the schema did not list is passed as "", so the select shows its placeholder. */
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
 * One scalar key: a secret, choices, a Discord id, a colour, a boolean or text, in that precedence.
 *
 * `edited` tells an untouched empty secret from a deliberate deletion.
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

  /** Declared choices win over the Discord picker, being more specific than a guess from the name. */
  if (entry.choices) {
    return <ChoicesControl id={id} value={value} choices={entry.choices} disabled={disabled} onChange={onChange} />
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

  /** Decided on the stored value, not the draft; see {@link colourValue}. */
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

  /** A value that already spans lines gets a box it fits in. */
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

/**
 * A list, one row per entry.
 *
 * The whole list is sent with the file's `revision`, so a concurrent save gets a 409.
 */
export function ListControl({
  id,
  items,
  disabled,
  onChange,
}: {
  id: string
  items: string[]
  disabled: boolean
  onChange: (items: string[]) => void
}) {
  return (
    <div className="flex flex-col gap-2">
      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">Empty list.</p>
      ) : (
        items.map((item, index) => (
          <div key={index} className="flex items-center gap-2">
            <Input
              id={index === 0 ? id : undefined}
              disabled={disabled}
              value={item}
              spellCheck={false}
              className="font-mono text-sm"
              aria-label={`Entry ${index + 1}`}
              onChange={(event) => onChange(items.map((old, at) => (at === index ? event.target.value : old)))}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={disabled}
              aria-label={`Remove entry ${index + 1}`}
              onClick={() => onChange(items.filter((_, at) => at !== index))}
            >
              <TrashIcon aria-hidden />
            </Button>
          </div>
        ))
      )}
      <div>
        <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onChange([...items, ""])}>
          <PlusIcon aria-hidden />
          Add entry
        </Button>
      </div>
    </div>
  )
}
