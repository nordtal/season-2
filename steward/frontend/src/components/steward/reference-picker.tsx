import { CheckIcon, PlusIcon, XIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import type { Icon } from "@phosphor-icons/react"
import { type ReactNode, useMemo, useState } from "react"

import type { ConfigReference, GameIcons } from "@/lib/api"
import {
  type Choice,
  type Choices,
  choiceFor,
  gameChoices,
  guildChoices,
  isGameReference,
  matching,
  namespacesOf,
  peopleChoices,
  registryOf,
} from "@/lib/references"
import { useGameData, useGuildChannels, usePeople } from "@/lib/queries"
import { GameIcon } from "@/components/steward/game-icon"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { FilterBar, SearchField } from "@/components/steward/filter-bar"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { cn } from "@/lib/utils"
import { t } from "@/lib/texts"

/** How many rows a search draws at once. The rest wait for a narrower search. */
const SHOWN = 200

/** Radix refuses "" as an item value. */
const ALL = "*"

const GENERIC: Record<string, Icon> = {
  statistic: TERM_ICON.statistic,
  advancement: TERM_ICON.advancement,
}

function useChoices(
  reference: ConfigReference,
  sibling: string | undefined,
): { choices: Choices; icons?: GameIcons; pending: boolean } {
  const kind = reference.to
  const game = useGameData(isGameReference(reference))
  const channels = useGuildChannels(kind === "DISCORD_CHANNEL")
  const people = usePeople(kind === "DISCORD_USER")
  const choices = useMemo(() => {
    if (kind === "DISCORD_CHANNEL") return guildChoices(channels.data)
    if (kind === "DISCORD_USER") return peopleChoices(people.data)
    return gameChoices(game.data, registryOf(reference, game.data, sibling), reference.except)
  }, [kind, reference, sibling, game.data, channels.data, people.data])
  const asked = kind === "DISCORD_CHANNEL" ? channels : kind === "DISCORD_USER" ? people : game
  return { choices, icons: game.data?.icons, pending: asked.isPending }
}

function Mark({
  choice,
  icons,
  registry,
  size = 24,
}: {
  choice: Choice
  icons: GameIcons | undefined
  registry: string | undefined
  size?: number
}) {
  if (choice.channel === "voice")
    return <TERM_ICON.voiceChannel aria-hidden className="size-4 shrink-0 text-muted-foreground" />
  if (choice.channel === "text")
    return <TERM_ICON.textChannel aria-hidden className="size-4 shrink-0 text-muted-foreground" />
  if (icons === undefined) return null
  if (choice.icon === undefined && registry !== undefined) {
    const Generic = GENERIC[registry] ?? TERM_ICON.item
    return (
      <span aria-hidden className="flex shrink-0 items-center justify-center" style={{ width: size, height: size }}>
        <Generic className="size-[70%] text-muted-foreground" />
      </span>
    )
  }
  return <GameIcon icons={icons} item={choice.icon} size={size} />
}

function Chip({
  value,
  choice,
  icons,
  registry,
  onRemove,
  disabled,
}: {
  value: string
  choice: Choice | undefined
  icons: GameIcons | undefined
  registry: string | undefined
  onRemove?: () => void
  disabled: boolean
}) {
  return (
    <span
      className={cn(
        "inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-md border py-0.5 pr-1 pl-1.5 text-sm",
        choice ? "border-border bg-muted/40" : "border-warning/40 bg-warning/12 text-warning",
      )}
      title={value}
    >
      {choice ? (
        <Mark choice={choice} icons={icons} registry={registry} size={20} />
      ) : (
        <TERM_ICON.warning aria-hidden className="size-4 shrink-0" />
      )}
      <span className={cn("truncate", !choice && "font-mono text-xs")}>{choice?.name ?? value}</span>
      {onRemove ? (
        <button
          type="button"
          disabled={disabled}
          aria-label={`Remove ${choice?.name ?? value}`}
          className="rounded-sm p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground disabled:pointer-events-none"
          onClick={onRemove}
        >
          <XIcon aria-hidden className="size-3.5" />
        </button>
      ) : null}
    </span>
  )
}

export function ReferenceValues({
  reference,
  values,
  sibling,
}: {
  reference: ConfigReference
  values: string[]
  sibling?: string
}) {
  const { choices, icons, pending } = useChoices(reference, sibling)
  if (pending) return <SkeletonText width="short" />
  /** When nothing is listed at all, the values are not marked unknown. */
  const listed = choices.choices.length > 0
  return (
    <span className="flex flex-wrap gap-1">
      {values.map((value, index) => {
        const choice = choiceFor(choices.choices, value)
        return listed ? (
          <Chip
            key={`${value}-${index}`}
            value={value}
            choice={choice}
            icons={icons}
            registry={choices.registry}
            disabled
          />
        ) : (
          <span key={`${value}-${index}`} className="font-mono text-xs">
            {value}
          </span>
        )
      })}
    </span>
  )
}

export function ReferenceMarks({
  reference,
  values,
  sibling,
  max = 3,
  named = false,
}: {
  reference: ConfigReference
  values: string[]
  sibling?: string
  max?: number
  named?: boolean
}) {
  const { choices, icons, pending } = useChoices(reference, sibling)
  if (pending) return <Skeleton className="size-5" />
  const shown = values.slice(0, max)
  return (
    <span className="inline-flex min-w-0 items-center gap-1">
      {shown.map((value, index) => {
        const choice = choiceFor(choices.choices, value)
        const name = choice?.name ?? value
        if (choices.choices.length > 0 && !choice) {
          return (
            <TERM_ICON.warning key={`${value}-${index}`} aria-label={value} className="size-5 shrink-0 text-warning" />
          )
        }
        return icons && choice?.icon ? (
          named ? (
            <span key={`${value}-${index}`} className="inline-flex min-w-0 items-center gap-1">
              <GameIcon icons={icons} item={choice.icon} size={20} />
              <span className="truncate text-xs">{name}</span>
            </span>
          ) : (
            <span key={`${value}-${index}`} title={name} className="shrink-0">
              <GameIcon icons={icons} item={choice.icon} size={20} />
            </span>
          )
        ) : (
          <span key={`${value}-${index}`} className="truncate text-xs">
            {name}
          </span>
        )
      })}
      {values.length > max ? (
        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">+{values.length - max}</span>
      ) : null}
    </span>
  )
}

/**
 * A list holds chips and adds values with the same picker. A value that nothing lists stays, marked, and is never replaced.
 */
export function ReferencePicker({
  id,
  label,
  reference,
  values,
  multi,
  sibling,
  disabled,
  typed,
  onChange,
}: {
  id: string
  label: string
  reference: ConfigReference
  values: string[]
  multi: boolean
  sibling?: string
  disabled: boolean
  typed: ReactNode
  onChange: (values: string[]) => void
}) {
  const { choices, icons, pending } = useChoices(reference, sibling)
  const [open, setOpen] = useState(false)

  if (pending) return <Skeleton className={multi ? "h-8 w-24" : "h-9 w-full"} />

  if (choices.unavailable !== undefined && choices.choices.length === 0) {
    return (
      <div className="flex flex-col gap-1.5">
        {typed}
        <p className="text-xs text-muted-foreground">{choices.unavailable}</p>
      </div>
    )
  }

  const known = (value: string) => choiceFor(choices.choices, value)
  const pick = (choice: Choice) => {
    if (!multi) {
      onChange([choice.id])
      setOpen(false)
      return
    }
    const at = values.findIndex((value) => known(value)?.id === choice.id)
    onChange(at >= 0 ? values.filter((_, index) => index !== at) : [...values, choice.id])
  }

  const sheet = (
    <ResponsiveDialog open={open} onOpenChange={setOpen}>
      <ResponsiveDialogContent className="sm:max-w-lg">
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{label}</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>
        <Options
          label={label}
          choices={choices}
          icons={icons}
          selected={new Set(values.map((value) => known(value)?.id ?? value))}
          multi={multi}
          clearable={!multi && values.some((value) => value !== "")}
          onPick={pick}
          onAddAll={(ids) => onChange([...values, ...ids.filter((candidate) => !values.includes(candidate))])}
          onClear={() => {
            onChange([])
            setOpen(false)
          }}
        />
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )

  if (multi) {
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        {values.map((value, index) => (
          <Chip
            key={`${value}-${index}`}
            value={value}
            choice={known(value)}
            icons={icons}
            registry={choices.registry}
            disabled={disabled}
            onRemove={() => onChange(values.filter((_, at) => at !== index))}
          />
        ))}
        <Button
          id={id}
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          aria-label={t("steward.settings.add-to", { what: label })}
          onClick={() => setOpen(true)}
        >
          <PlusIcon aria-hidden />
          {t("steward.settings.add")}
        </Button>
        {sheet}
      </div>
    )
  }

  const value = values[0] ?? ""
  const choice = value === "" ? undefined : known(value)
  return (
    <>
      <Button
        id={id}
        type="button"
        variant="outline"
        disabled={disabled}
        className="h-auto min-h-9 w-full min-w-0 justify-start gap-2 py-1 font-normal"
        onClick={() => setOpen(true)}
      >
        {value === "" ? (
          <span className="text-muted-foreground">{t("steward.settings.none")}</span>
        ) : choice ? (
          <>
            <Mark choice={choice} icons={icons} registry={choices.registry} />
            <span className="truncate">{choice.name}</span>
            <span className="ml-auto truncate font-mono text-xs text-muted-foreground max-sm:hidden">{choice.id}</span>
          </>
        ) : (
          <span className="flex min-w-0 items-center gap-1.5 text-warning">
            <TERM_ICON.warning aria-hidden className="size-4 shrink-0" />
            <span className="truncate font-mono text-xs">{value}</span>
          </span>
        )}
      </Button>
      {sheet}
    </>
  )
}

function Options({
  label,
  choices,
  icons,
  selected,
  multi,
  clearable,
  onPick,
  onAddAll,
  onClear,
}: {
  label: string
  choices: Choices
  icons: GameIcons | undefined
  selected: ReadonlySet<string>
  multi: boolean
  clearable: boolean
  onPick: (choice: Choice) => void
  onAddAll: (ids: string[]) => void
  onClear: () => void
}) {
  const [query, setQuery] = useState("")
  const [namespace, setNamespace] = useState(ALL)
  const [tag, setTag] = useState(ALL)
  const namespaces = useMemo(() => namespacesOf(choices.choices), [choices.choices])

  const filtered = useMemo(() => {
    const members = tag === ALL ? null : new Set(choices.tags.find((candidate) => candidate.id === tag)?.values ?? [])
    const inScope = choices.choices.filter(
      (choice) =>
        (namespace === ALL || choice.id.startsWith(`${namespace}:`)) && (members === null || members.has(choice.id)),
    )
    return matching(inScope, query)
  }, [choices, namespace, tag, query])
  const asTree = choices.tree && query.trim() === "" && tag === ALL

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      <FilterBar>
        <SearchField value={query} onValueChange={setQuery} label={t("steward.settings.search-in", { what: label })} />
      </FilterBar>
      {namespaces.length > 1 || choices.tags.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1.5">
          {namespaces.length > 1
            ? [ALL, ...namespaces].map((name) => (
                <Button
                  key={name}
                  type="button"
                  size="xs"
                  variant={namespace === name ? "secondary" : "ghost"}
                  aria-pressed={namespace === name}
                  onClick={() => setNamespace(name)}
                >
                  {name === ALL ? t("steward.settings.all") : name}
                </Button>
              ))
            : null}
          {choices.tags.length > 0 ? (
            <Select value={tag} onValueChange={setTag}>
              <SelectTrigger
                size="sm"
                aria-label={t("steward.settings.tag")}
                className="min-w-0 flex-1 font-mono text-xs"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>{t("steward.settings.every-tag")}</SelectItem>
                {choices.tags.map((candidate) => (
                  <SelectItem key={candidate.id} value={candidate.id} className="font-mono text-xs">
                    #{candidate.id}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : null}
          {multi && tag !== ALL && filtered.length > 0 ? (
            <Button
              type="button"
              size="xs"
              variant="outline"
              onClick={() => onAddAll(filtered.map((choice) => choice.id))}
            >
              <PlusIcon aria-hidden />
              {filtered.length}
            </Button>
          ) : null}
        </div>
      ) : null}
      <div
        role="listbox"
        aria-label={label}
        aria-multiselectable={multi}
        className="-mx-1 max-h-[55svh] min-h-0 flex-1 overflow-y-auto sm:max-h-96 in-data-[slot=drawer-content]:max-h-none"
      >
        {clearable ? (
          <button
            type="button"
            role="option"
            aria-selected={false}
            className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-muted-foreground hover:bg-muted"
            onClick={onClear}
          >
            {t("steward.settings.none")}
          </button>
        ) : null}
        {filtered.slice(0, SHOWN).map((choice) => {
          const on = selected.has(choice.id)
          return (
            <button
              key={choice.id}
              type="button"
              role="option"
              aria-selected={on}
              className={cn(
                "flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted",
                on && "bg-muted",
              )}
              style={asTree && choice.depth ? { paddingLeft: `${0.5 + choice.depth * 1.25}rem` } : undefined}
              onClick={() => onPick(choice)}
            >
              <Mark choice={choice} icons={icons} registry={choices.registry} />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate">{choice.name}</span>
                {choice.description ? (
                  <span className="truncate text-xs text-muted-foreground">{choice.description}</span>
                ) : null}
              </span>
              {choice.frame && choice.frame !== "task" ? (
                <span
                  className={cn(
                    "shrink-0 rounded-sm px-1 text-xs",
                    choice.frame === "challenge" ? "bg-primary/15 text-primary" : "bg-muted text-muted-foreground",
                  )}
                >
                  {choice.frame}
                </span>
              ) : null}
              <span className="max-w-[45%] min-w-0 truncate font-mono text-xs text-muted-foreground max-sm:max-w-[40%]">
                {choice.id}
              </span>
              {multi ? (
                <CheckIcon aria-hidden className={cn("size-4 shrink-0", on ? "opacity-100" : "opacity-0")} />
              ) : null}
            </button>
          )
        })}
        {filtered.length === 0 ? (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">{t("steward.settings.no-match")}</p>
        ) : null}
        {filtered.length > SHOWN ? (
          <p className="px-2 py-2 text-center text-xs text-muted-foreground">+{filtered.length - SHOWN}</p>
        ) : null}
      </div>
    </div>
  )
}
