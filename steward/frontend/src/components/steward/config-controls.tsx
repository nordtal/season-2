import { PlusIcon, TrashIcon } from "@phosphor-icons/react"

import type { ConfigChoices, ConfigEntry } from "@/lib/api"
import { isColour } from "@/lib/references"
import { useGameData } from "@/lib/queries"
import { ColourControl } from "@/components/steward/colour-control"
import { GameIcon } from "@/components/steward/game-icon"
import { ReferencePicker } from "@/components/steward/reference-picker"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { fileTitle } from "@/lib/words"
import { t } from "@/lib/texts"

/** `configuration.tsx` and `repeatable-cards.tsx` share it, so neither imports the other. */

/**
 * Built like `Labels.of`: no extension, words split, Capital Case. It is here so that `config-search.tsx` can import it without a cycle.
 */
export function humanFileName(name: string): string {
  return fileTitle(name)
}

export function explanationOf(entry: ConfigEntry): string | null {
  if (entry.noExplanationNeeded) return null
  if (entry.explanation) return entry.explanation
  return null
}

/**
 * Returns whether an empty value leaves a section incomplete. That is the case for a Discord channel that its spec does not mark optional.
 *
 * A language without a channel cannot carry its messages. The game judges an empty game reference.
 */
export function isRequiredChannel(entry: ConfigEntry): boolean {
  return entry.refers?.to === "DISCORD_CHANNEL" && !entry.refers.optional
}

export function choiceName(choices: ConfigChoices, value: string): string {
  return choices.names?.[value] ?? value
}

export function ChoiceIcon({ choices, value, size = 20 }: { choices: ConfigChoices; value: string; size?: number }) {
  const item = choices.icons?.[value]
  const game = useGameData(choices.icons !== undefined)
  if (item === undefined || game.data?.icons === undefined) return null
  return <GameIcon icons={game.data.icons} item={item} size={size} />
}

/** A strict list is a select only. A suggestion list is a select beside a free text field. */
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
  /**
   * A value that the schema does not list is passed as "", so the select shows its placeholder.
   */
  const known = choices.values.includes(value)
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Select value={known ? value : ""} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={choices.strict ? id : undefined} className="min-w-48">
          <SelectValue
            placeholder={choices.strict ? t("steward.settings.choose-one") : t("steward.settings.choose-suggestion")}
          />
        </SelectTrigger>
        <SelectContent>
          {choices.values.map((option) => (
            <SelectItem key={option} value={option}>
              <ChoiceIcon choices={choices} value={option} />
              {choiceName(choices, option)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {choices.strict ? null : (
        <Input
          id={id}
          aria-label={t("steward.settings.free-text")}
          disabled={disabled}
          value={value}
          spellCheck={false}
          className="min-w-40 flex-1 font-mono text-sm max-md:text-base"
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </div>
  )
}

/** A reference falls back to it while nothing can be listed. */
function TextControl({
  id,
  entry,
  value,
  disabled,
  onChange,
}: {
  id: string
  entry: ConfigEntry
  value: string
  disabled: boolean
  onChange: (value: string) => void
}) {
  if (value.includes("\n")) {
    return (
      <Textarea
        id={id}
        rows={Math.min(16, value.split("\n").length + 1)}
        disabled={disabled}
        value={value}
        spellCheck={false}
        className="font-mono text-sm max-md:text-base"
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
      className="font-mono text-sm max-md:text-base"
      onChange={(event) => onChange(event.target.value)}
    />
  )
}

/**
 * The precedence is: secret, choices, reference, boolean, text. `sibling` is the value of the field that a reference depends on.
 */
export function ScalarControl({
  id,
  entry,
  value,
  disabled,
  sibling,
  onChange,
}: {
  id: string
  entry: ConfigEntry
  value: string
  disabled: boolean
  sibling?: string
  onChange: (value: string) => void
}) {
  /** A secret lives only in the host environment, so the form shows only whether it is set. */
  if (entry.secret) {
    return (
      <Input
        id={id}
        type="password"
        disabled
        value=""
        placeholder={entry.filled ? t("steward.settings.set") : t("steward.settings.not-set")}
      />
    )
  }

  if (entry.choices) {
    return <ChoicesControl id={id} value={value} choices={entry.choices} disabled={disabled} onChange={onChange} />
  }

  if (isColour(entry.refers)) {
    return <ColourControl id={id} value={value} disabled={disabled} onChange={onChange} />
  }

  if (entry.refers) {
    return (
      <ReferencePicker
        id={id}
        label={entry.label}
        reference={entry.refers}
        values={value === "" ? [] : [value]}
        multi={false}
        sibling={sibling}
        disabled={disabled}
        typed={<TextControl id={id} entry={entry} value={value} disabled={disabled} onChange={onChange} />}
        onChange={(values) => onChange(values[0] ?? "")}
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

  return <TextControl id={id} entry={entry} value={value} disabled={disabled} onChange={onChange} />
}

/** The form sends the whole list with the file's `revision`, so a concurrent save gets a 409. */
export function ListControl({
  id,
  entry,
  items,
  disabled,
  sibling,
  onChange,
}: {
  id: string
  entry: ConfigEntry
  items: string[]
  disabled: boolean
  sibling?: string
  onChange: (items: string[]) => void
}) {
  const rows = <ListRows id={id} items={items} disabled={disabled} onChange={onChange} />
  if (!entry.refers || isColour(entry.refers)) return rows
  return (
    <ReferencePicker
      id={id}
      label={entry.label}
      reference={entry.refers}
      values={items}
      multi
      sibling={sibling}
      disabled={disabled}
      typed={rows}
      onChange={onChange}
    />
  )
}

function ListRows({
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
        <p className="text-sm text-muted-foreground">{t("steward.settings.empty-list")}</p>
      ) : (
        items.map((item, index) => (
          <div key={index} className="flex items-center gap-2">
            <Input
              id={index === 0 ? id : undefined}
              disabled={disabled}
              value={item}
              spellCheck={false}
              className="font-mono text-sm max-md:text-base"
              aria-label={t("steward.settings.entry", { index: index + 1 })}
              onChange={(event) => onChange(items.map((old, at) => (at === index ? event.target.value : old)))}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={disabled}
              aria-label={t("steward.settings.remove-entry", {
                index: index + 1,
              })}
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
          {t("steward.settings.add-entry")}
        </Button>
      </div>
    </div>
  )
}
