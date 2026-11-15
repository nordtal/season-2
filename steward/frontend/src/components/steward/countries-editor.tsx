import { useState } from "react"

import type { ConfigDocument, ConfigEntry, GlyphInfo } from "@/lib/api"
import { useDocuments, useGlyphs } from "@/lib/queries"
import { mostEntries } from "@/lib/setting-rules"
import { t } from "@/lib/texts"
import { Control, draftSections } from "@/components/steward/configuration"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { ScalarControl, explanationOf } from "@/components/steward/config-controls"
import { QueryState } from "@/components/steward/query-state"
import { GroupEditor } from "@/components/steward/group-draft"
import { EntryDialog, Labelled } from "@/components/steward/game-groups/tiles"
import { listEntry, text } from "@/components/steward/game-groups/model"
import { GlyphTile } from "@/components/steward/message-editor/preview"
import { type SectionValues, blankSection } from "@/components/steward/repeatable-cards"
import { PencilIcon } from "@/components/ui/icons"
import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

const KEYS = ["key", "name", "zone", "language", "emoji", "glyph"]

export function readsCountries(document: ConfigDocument): boolean {
  return listEntry(document.entries, KEYS) !== undefined
}

export function CountriesEditor({ file, document }: CustomEditorProps) {
  const list = listEntry(document.entries, KEYS)
  const glyphs = useGlyphs()
  const languages = useDocuments()
  if (list === undefined) return null
  return (
    <GroupEditor file={file} document={document}>
      {(draft, set) => (
        <>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {document.entries
              .filter((entry) => entry !== list)
              .map((entry) => (
                <Labelled key={entry.path} id={entry.path} label={entry.label}>
                  {explanationOf(entry) ? (
                    <p className="text-xs text-muted-foreground">{explanationOf(entry)}</p>
                  ) : null}
                  <Control
                    entry={entry}
                    draft={draft}
                    disabled={!document.writable}
                    onChange={(value) => set(entry.path, value)}
                  />
                </Labelled>
              ))}
          </div>
          <QueryState query={glyphs}>
            {(released) => (
              <QueryState query={languages}>
                {(spoken) => (
                  <Countries
                    list={list}
                    countries={draftSections(draft, list)}
                    disabled={!document.writable}
                    glyphs={(released ?? []).filter((glyph) => glyph.name.startsWith("flag-"))}
                    languages={spoken?.languages ?? []}
                    onChange={(next) => set(list.path, next)}
                  />
                )}
              </QueryState>
            )}
          </QueryState>
        </>
      )}
    </GroupEditor>
  )
}

export function Countries({
  list,
  countries,
  disabled,
  glyphs,
  languages,
  onChange,
}: {
  list: ConfigEntry
  countries: SectionValues[]
  disabled: boolean
  glyphs: GlyphInfo[]
  languages: string[]
  onChange: (next: SectionValues[]) => void
}) {
  const most = mostEntries(list.rules)
  const [chosen, choose] = useState<number | null>(null)
  const open = chosen === null ? undefined : countries[chosen]
  const flag = (country: SectionValues) => glyphs.find((glyph) => glyph.name === text(country, "glyph"))
  const mark = (country: SectionValues) => {
    const glyph = flag(country)
    return (
      <span className="flex shrink-0 items-center gap-2">
        <span aria-hidden className="text-xl">
          {text(country, "emoji")}
        </span>
        {glyph ? <GlyphTile glyph={glyph} className="size-8" /> : null}
      </span>
    )
  }
  const update = (key: string, value: string) => {
    if (key === "language" && !languages.includes(value)) return
    if (key === "glyph" && !glyphs.some((glyph) => glyph.name === value)) return
    onChange(countries.map((country, index) => (index === chosen ? { ...country, [key]: value } : country)))
  }
  return (
    <section className="flex flex-col gap-3" aria-label={list.label}>
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium">{list.label}</h3>
        <Button
          type="button"
          variant="outline"
          disabled={disabled || (most !== undefined && countries.length >= most)}
          onClick={() => {
            onChange([...countries, blankSection(list.template ?? [])])
            choose(countries.length)
          }}
        >
          {t("steward.settings.add-country")}
        </Button>
      </div>
      <ul className="divide-y rounded-lg border">
        {countries.map((country, index) => (
          <li key={index}>
            <button
              type="button"
              aria-haspopup="dialog"
              className="flex w-full min-w-0 items-center gap-3 px-3 py-2 text-left hover:bg-muted/50"
              onClick={() => choose(index)}
            >
              {mark(country)}
              <span className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="truncate text-sm font-medium">
                  {text(country, "name") || text(country, "key") || list.label}
                </span>
                <span className="truncate text-xs text-muted-foreground">{text(country, "zone")}</span>
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">{text(country, "language")}</span>
              <PencilIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
            </button>
          </li>
        ))}
      </ul>
      {open ? (
        <EntryDialog
          open
          mark={mark(open)}
          name={text(open, "name") || list.label}
          disabled={disabled}
          onClose={() => choose(null)}
          onRemove={() => onChange(countries.filter((_, index) => index !== chosen))}
        >
          {(list.template ?? []).map((entry) => {
            const id = `country-${chosen}-${entry.key}`
            const value = text(open, entry.key)
            const options =
              entry.key === "language" ? languages : entry.key === "glyph" ? glyphs.map((glyph) => glyph.name) : null
            return (
              <Labelled key={entry.key} id={id} label={entry.label}>
                {options ? (
                  <Select
                    value={options.includes(value) ? value : ""}
                    onValueChange={(next) => update(entry.key, next)}
                    disabled={disabled || options.length === 0}
                  >
                    <SelectTrigger id={id}>
                      <SelectValue placeholder={t("steward.settings.choose-one")} />
                    </SelectTrigger>
                    <SelectContent>
                      {options.map((option) => (
                        <SelectItem key={option} value={option}>
                          {entry.key === "glyph" ? (
                            <GlyphTile glyph={glyphs.find((glyph) => glyph.name === option)!} className="size-6" />
                          ) : null}
                          {option}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <ScalarControl
                    id={id}
                    entry={entry}
                    value={value}
                    disabled={disabled}
                    onChange={(next) => update(entry.key, next)}
                  />
                )}
              </Labelled>
            )
          })}
        </EntryDialog>
      ) : null}
    </section>
  )
}
