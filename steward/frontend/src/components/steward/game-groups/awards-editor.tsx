import { useMemo, useState } from "react"

import type { ConfigDocument, ConfigEntry, ConfigReference } from "@/lib/api"
import { type Choice, choiceFor, matching, nameOfId } from "@/lib/references"
import { draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { FilterBar, SearchField } from "@/components/steward/filter-bar"
import { GroupEditor } from "@/components/steward/group-draft"
import { Pill } from "@/components/steward/milestones/parts"
import { Empty } from "@/components/steward/query-state"
import { Mark, useChoices } from "@/components/steward/reference-picker"
import { type SectionValues, blankSection, notSaved } from "@/components/steward/repeatable-cards"
import { TERM_ICON } from "@/components/steward/term-icons"
import { count, field, listEntry, sum, text } from "@/components/steward/game-groups/model"
import { AddPicker, NumberInput } from "@/components/steward/game-groups/parts"
import { EntryDialog, Labelled, Tile, Tiles } from "@/components/steward/game-groups/tiles"
import { t } from "@/lib/texts"

const KEYS = ["advancement", "aura"]

export function readsAwards(document: ConfigDocument): boolean {
  const list = listEntry(document.entries, KEYS)
  return list !== undefined && field(list, "advancement")?.refers !== undefined
}

export function AwardsEditor({ file, document }: CustomEditorProps) {
  const list = listEntry(document.entries, KEYS)
  if (list === undefined) return null
  return (
    <GroupEditor file={file} document={document}>
      {(draft, set) => (
        <Awards
          list={list}
          awards={draftSections(draft, list)}
          disabled={!document.writable}
          onChange={(next) => set(list.path, next)}
        />
      )}
    </GroupEditor>
  )
}

/** One tile per award in stored order, with its aura and spins; a tile opens the award's dialog, and the search narrows the tiles by name or ID. */
function Awards({
  list,
  awards,
  disabled,
  onChange,
}: {
  list: ConfigEntry
  awards: SectionValues[]
  disabled: boolean
  onChange: (next: SectionValues[]) => void
}) {
  const [query, setQuery] = useState("")
  const [chosen, choose] = useState<number | null>(null)
  const reference = field(list, "advancement")!.refers!
  const { choices, icons } = useChoices(reference, undefined)
  const awarded = useMemo(() => awards.map((award) => text(award, "advancement")), [awards])
  const unawarded: ConfigReference = useMemo(
    () => ({ ...reference, except: [...(reference.except ?? []), ...awarded] }),
    [reference, awarded],
  )
  const rows = useMemo(
    () =>
      awards.map((award, index) => {
        const id = text(award, "advancement")
        const choice = choiceFor(choices.choices, id)
        return { award, index, id, choice, name: choice?.name ?? nameOfId(id) }
      }),
    [awards, choices.choices],
  )
  const shown = useMemo(() => {
    if (query.trim() === "") return rows
    const hits = new Set(
      matching(
        rows.map((row) => ({ id: row.id, name: row.name })),
        query,
      ).map((hit) => hit.id),
    )
    return rows.filter((row) => hits.has(row.id))
  }, [rows, query])

  const update = (index: number, key: string, value: string) =>
    onChange(awards.map((award, at) => (at === index ? { ...award, [key]: value } : award)))
  const spins = field(list, "spins")
  const mark = (choice: Choice | undefined, size: number) =>
    choice ? (
      <Mark choice={choice} icons={icons} registry={choices.registry} size={size} />
    ) : (
      <TERM_ICON.warning aria-hidden className="size-10 shrink-0 text-warning" />
    )
  const open = chosen === null ? undefined : rows[chosen]

  return (
    <div className="@container flex flex-col gap-3">
      <FilterBar>
        <Pill tip={t("steward.game.awarded", { count: awards.length })}>
          <TERM_ICON.advancement aria-hidden className="size-3.5 shrink-0" />
          {awards.length.toLocaleString("en")}
        </Pill>
        <Pill tip={t("steward.game.aura-awarded", { total: sum(awards, "aura") })}>
          <TERM_ICON.aura aria-hidden className="size-3.5 shrink-0" />
          {sum(awards, "aura").toLocaleString("en")}
        </Pill>
        {spins ? (
          <Pill tip={t("steward.game.spins-awarded", { total: sum(awards, "spins") })}>
            <TERM_ICON.spins aria-hidden className="size-3.5 shrink-0" />
            {sum(awards, "spins").toLocaleString("en")}
          </Pill>
        ) : null}
        <SearchField
          value={query}
          onValueChange={setQuery}
          label={t("steward.settings.search-in", { what: list.label })}
        />
        <AddPicker
          id={`${list.path}-add`}
          label={t("steward.game.advancement")}
          reference={unawarded}
          disabled={disabled}
          onPick={(id) => {
            onChange([...awards, { ...blankSection(list.template ?? []), advancement: id }])
            choose(awards.length)
          }}
        />
      </FilterBar>
      {awards.length === 0 ? (
        <Empty title={t("steward.game.no-awards")} />
      ) : shown.length === 0 ? (
        <Empty title={t("steward.settings.no-match")} />
      ) : (
        <Tiles>
          {shown.map(({ award, index, id, choice, name }) => (
            <Tile
              key={`${id}-${index}`}
              mark={mark(choice, 40)}
              name={name}
              onOpen={() => choose(index)}
              details={
                <>
                  <Amount term="aura" value={count(award, "aura")} />
                  {spins ? <Amount term="spins" value={count(award, "spins")} /> : null}
                </>
              }
            />
          ))}
        </Tiles>
      )}
      {open ? (
        <EntryDialog
          open
          mark={mark(open.choice, 48)}
          name={open.name}
          description={open.choice?.description}
          disabled={disabled}
          onRemove={() => onChange(awards.filter((_, at) => at !== open.index))}
          onClose={() => choose(null)}
        >
          <div className="grid grid-cols-2 gap-2">
            {(["aura", "spins"] as const).map((key) => {
              const entry = field(list, key)
              if (entry === undefined) return null
              const id = `${list.path}-${open.index}-${key}`
              return (
                <Labelled key={key} id={id} label={entry.label}>
                  <NumberInput
                    id={id}
                    label={t(key === "aura" ? "steward.game.aura-of" : "steward.game.spins-of", { name: open.name })}
                    term={key}
                    value={text(open.award, key)}
                    entry={entry}
                    unsaved={notSaved(list, open.index, key, text(open.award, key))}
                    disabled={disabled}
                    className="w-full"
                    onChange={(value) => update(open.index, key, value)}
                  />
                </Labelled>
              )
            })}
          </div>
        </EntryDialog>
      ) : null}
    </div>
  )
}

/** A term's value on a tile, drawn with the term's icon. */
function Amount({ term, value }: { term: "aura" | "spins"; value: number }) {
  const Icon = TERM_ICON[term]
  return (
    <span className="inline-flex items-center gap-1">
      <Icon aria-hidden className="size-3.5 shrink-0 text-muted-foreground" />
      {value.toLocaleString("en")}
    </span>
  )
}
