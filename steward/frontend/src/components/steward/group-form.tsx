import { useMemo, useState } from "react"

import type { ConfigChanges, ConfigEntry, ConfigDocument } from "@/lib/api"
import { useConfig, useConfigs } from "@/lib/queries"
import { Badge } from "@/components/ui/badge"

/** A form over one published settings group, as the backup and the update schedules draw theirs. */

/** Steward's own group, looked up in `/api/setting-groups` so a group not yet published gets no form. */
export function useStewardConfig() {
  return useGroupConfig("steward", "steward")
}

/** One service's group, looked up in `/api/setting-groups` so a group not yet published gets no form. */
export function useGroupConfig(service: string, name: string) {
  const configs = useConfigs()
  const file = configs.data?.find((location) => location.service === service && location.name === name)
  const document = useConfig(file?.path ?? "", Boolean(file))
  return { file: file?.path, document: document.data, pending: configs.isPending || document.isPending }
}

/** One key of that file by its dotted path, or `undefined` when there is none. */
export function entryAt(document: ConfigDocument | undefined, path: string): ConfigEntry | undefined {
  return document?.entries.find((entry) => entry.path === path)
}

/**
 * The draft, changes and save state for a fixed list of keys of one document.
 *
 * `keys` must be a stable reference, or the memo is invalidated every render.
 */
export function useConfigDraft(document: ConfigDocument | undefined, keys: readonly string[]) {
  const [draft, setDraft] = useState<Record<string, string>>({})

  /** The answer to a save is the group as stored, so a write resets the form to it. */
  const [lastDocument, setLastDocument] = useState(document)
  if (lastDocument !== document) {
    setLastDocument(document)
    setDraft({})
  }

  const entries = useMemo(
    () => keys.map((path) => entryAt(document, path)).filter((entry): entry is ConfigEntry => entry !== undefined),
    [document, keys],
  )

  const changes: ConfigChanges = {}
  for (const entry of entries) {
    const typed = draft[entry.path]
    if (typed === undefined) continue
    if (!entry.secret && typed !== (entry.value ?? "")) changes[entry.path] = typed
  }
  const changed = Object.keys(changes).length

  return { entries, draft, setDraft, changes, changed }
}

/** The seven days in week order, with their `java.time.DayOfWeek` names. */
const WEEKDAYS = [
  { label: "Mon", value: "MONDAY" },
  { label: "Tue", value: "TUESDAY" },
  { label: "Wed", value: "WEDNESDAY" },
  { label: "Thu", value: "THURSDAY" },
  { label: "Fri", value: "FRIDAY" },
  { label: "Sat", value: "SATURDAY" },
  { label: "Sun", value: "SUNDAY" },
] as const

/** The `backup.days` key, a list and therefore not part of the scalar draft. */
/** Which days a list of config entries means, by the first three letters, as `NightlyClock` reads them. */
export function chosenDays(items: string[] | undefined): string[] {
  if (items === undefined) return []
  const stems = new Set(items.map((item) => item.trim().slice(0, 3).toUpperCase()))
  return WEEKDAYS.filter((day) => stems.has(day.value.slice(0, 3))).map((day) => day.value)
}

/** The seven days as toggles, in week order; shared by the backup and update schedules. */
export function DayPicker({
  days,
  disabled,
  onChange,
}: {
  days: string[]
  disabled: boolean
  onChange: (days: string[]) => void
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {WEEKDAYS.map((day) => {
        const on = days.includes(day.value)
        return (
          <Badge
            key={day.value}
            asChild
            variant={on ? "default" : "outline"}
            className={disabled ? undefined : "cursor-pointer"}
          >
            <button
              type="button"
              aria-pressed={on}
              disabled={disabled}
              onClick={() =>
                onChange(
                  on
                    ? days.filter((chosen) => chosen !== day.value)
                    : WEEKDAYS.filter(
                        (candidate) => candidate.value === day.value || days.includes(candidate.value),
                      ).map((candidate) => candidate.value),
                )
              }
            >
              {day.label}
            </button>
          </Badge>
        )
      })}
    </div>
  )
}
