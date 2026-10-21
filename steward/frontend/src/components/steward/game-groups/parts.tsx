import { PlusIcon, TrashIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"

import type { ConfigEntry, ConfigReference } from "@/lib/api"
import { AskThenAct } from "@/components/steward/ask-then-act"
import { ReferencePicker } from "@/components/steward/reference-picker"
import { type SectionValues } from "@/components/steward/repeatable-cards"
import { type Term, TERM_ICON } from "@/components/steward/term-icons"
import { BOX, field, text } from "@/components/steward/game-groups/model"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

export function NumberInput({
  id,
  label,
  value,
  term,
  disabled,
  readOnly,
  className,
  onChange,
}: {
  id?: string
  label: string
  value: string
  term?: Term
  disabled: boolean
  readOnly?: boolean
  className?: string
  onChange?: (value: string) => void
}) {
  const Mark = term === undefined ? undefined : TERM_ICON[term]
  return (
    <InputGroup className={cn("w-24 shrink-0", className)}>
      {Mark ? (
        <InputGroupAddon>
          <Mark aria-hidden />
        </InputGroupAddon>
      ) : null}
      <InputGroupInput
        id={id}
        aria-label={label}
        inputMode="numeric"
        value={value}
        disabled={disabled}
        readOnly={readOnly}
        className="text-right font-mono tabular-nums"
        onChange={(event) => onChange?.(event.target.value)}
      />
    </InputGroup>
  )
}

export function RemoveButton({ name, disabled, onRemove }: { name: string; disabled: boolean; onRemove: () => void }) {
  return (
    <AskThenAct
      trigger={
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={t("steward.settings.remove", { what: name })}
          disabled={disabled}
        >
          <TrashIcon aria-hidden />
        </Button>
      }
      title={t("steward.game.remove-ask", { name })}
      cancel={t("steward.game.keep")}
      action={t("steward.game.remove-it")}
      act={onRemove}
    />
  )
}

/** A picker that reads as a row's name: no frame of its own inside a framed list. */
export const BARE = "min-w-0 flex-1 border-transparent bg-transparent shadow-none dark:border-transparent dark:bg-transparent"

/**
 * Picks one game value. `face` is what the button shows; without a catalogue the value is typed.
 */
export function GamePicker({
  id,
  label,
  reference,
  value,
  disabled,
  face,
  className,
  onPick,
}: {
  id: string
  label: string
  reference: ConfigReference
  value: string
  disabled: boolean
  face: ReactNode
  className?: string
  onPick: (value: string) => void
}) {
  return (
    <ReferencePicker
      id={id}
      label={label}
      reference={reference}
      values={value === "" ? [] : [value]}
      multi={false}
      disabled={disabled}
      face={face}
      className={className}
      typed={
        <Input
          id={id}
          aria-label={label}
          value={value}
          disabled={disabled}
          className="font-mono"
          onChange={(event) => onPick(event.target.value)}
        />
      }
      onChange={(values) => {
        if (values[0] !== undefined) onPick(values[0])
      }}
    />
  )
}

/** The button that adds one picked value as a new entry. */
export function AddPicker({
  id,
  label,
  reference,
  disabled,
  onPick,
}: {
  id: string
  label: string
  reference: ConfigReference
  disabled: boolean
  onPick: (value: string) => void
}) {
  return (
    <GamePicker
      id={id}
      label={label}
      reference={reference}
      value=""
      disabled={disabled}
      className="h-8 min-h-8 w-auto shrink-0"
      face={
        <>
          <PlusIcon aria-hidden />
          {label}
        </>
      }
      onPick={onPick}
    />
  )
}

/**
 * A platform's or a region's world and corners. The labels come from the schema, so each field reads "Min X" to a screen reader.
 */
export function BoxFields({
  id,
  list,
  section,
  disabled,
  onChange,
}: {
  id: string
  list: ConfigEntry
  section: SectionValues
  disabled: boolean
  onChange: (key: string, value: string) => void
}) {
  const label = (key: string) => field(list, key)?.label ?? key
  return (
    <div className="flex flex-col gap-2">
      <Input
        id={`${id}-world`}
        aria-label={label("world")}
        value={text(section, "world")}
        disabled={disabled}
        className="font-mono"
        onChange={(event) => onChange("world", event.target.value)}
      />
      {(["min", "max"] as const).map((corner) => (
        <div key={corner} className="grid grid-cols-[2.5rem_repeat(3,minmax(0,1fr))] items-center gap-2">
          <span className="text-xs text-muted-foreground">
            {corner === "min" ? t("steward.game.min") : t("steward.game.max")}
          </span>
          {BOX[corner].map((key) => (
            <NumberInput
              key={key}
              label={label(key)}
              value={text(section, key)}
              disabled={disabled}
              className="w-full"
              onChange={(value) => onChange(key, value)}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

export function Heading({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex min-h-8 flex-wrap items-center gap-2">
      <h3 className="mr-auto text-sm font-medium">{children}</h3>
      {actions}
    </div>
  )
}
