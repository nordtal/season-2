import { useState } from "react"
import { EyeIcon, EyeSlashIcon } from "@phosphor-icons/react"
import type { ConfigEntry } from "@/lib/api"
import { isColour } from "@/lib/references"
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@/components/ui/input-group"
import { t } from "@/lib/texts"

/** Minecraft's chat background: black at the default `textBackgroundOpacity` of 0.5. */
export const MINECRAFT_CHAT_BACKGROUND = "rgba(0, 0, 0, 0.5)"

/**
 * The word the preview paints. It is one word, so five tones side by side wrap to the same height.
 */
export const SAMPLE_TEXT = "Nordtal"

const HEX_COLOUR = /^#[0-9a-f]{6}$/i

function validHex(value: string): string | null {
  return HEX_COLOUR.test(value) ? value : null
}

export function ColourControl({
  id,
  value,
  disabled,
  onChange,
}: {
  id: string
  value: string
  disabled?: boolean
  onChange: (value: string) => void
}) {
  const valid = validHex(value)
  const [shown, setShown] = useState(false)

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        {/* The native picker accepts only `#rrggbb`, so it shows black for any other `value`. */}
        <input
          type="color"
          aria-label={t("steward.settings.pick-colour")}
          disabled={disabled}
          value={valid ?? "#000000"}
          onChange={(event) => onChange(event.target.value)}
          className="h-9 w-9 shrink-0 cursor-pointer rounded border border-input bg-transparent p-0.5 disabled:cursor-not-allowed disabled:opacity-50"
        />
        {/* `h-9` matches the swatch's height. The eye button stays enabled while the field is disabled, because it writes nothing. */}
        <InputGroup className="h-9 min-w-0 flex-1">
          <InputGroupInput
            id={id}
            disabled={disabled}
            value={value}
            spellCheck={false}
            placeholder="#rrggbb"
            className="font-mono text-sm"
            onChange={(event) => onChange(event.target.value)}
          />
          <InputGroupAddon align="inline-end">
            <InputGroupButton
              size="icon-xs"
              aria-pressed={shown}
              aria-label={t("steward.settings.preview", { shown })}
              onClick={() => setShown((it) => !it)}
            >
              {shown ? <EyeSlashIcon /> : <EyeIcon />}
            </InputGroupButton>
          </InputGroupAddon>
        </InputGroup>
      </div>
      {/* The box has the same size whether or not the digits are complete, so nothing below it moves while the admin types. */}
      {shown ? (
        <div
          role="img"
          aria-label={valid ? t("steward.settings.preview-on") : t("steward.settings.no-colour")}
          style={{ backgroundColor: MINECRAFT_CHAT_BACKGROUND }}
          className="w-full rounded px-2 py-1"
        >
          <span
            style={valid ? { color: valid } : undefined}
            className={`block truncate text-sm ${valid ? "" : "text-muted-foreground"}`}
          >
            {SAMPLE_TEXT}
          </span>
        </div>
      ) : null}
    </div>
  )
}

function parentOf(entry: ConfigEntry): string {
  return entry.path.includes(".") ? entry.path.slice(0, entry.path.lastIndexOf(".")) : ""
}

/**
 * An entry is a colour when its spec declares one, so an entry with a blank value stays in its group.
 */
export function colourRuns(entries: ConfigEntry[]): ConfigEntry[][] {
  const runs: ConfigEntry[][] = []
  let current: ConfigEntry[] = []

  for (const entry of entries) {
    const colour = entry.kind === "SCALAR" && entry.editable && !entry.secret && isColour(entry.refers)
    const continuesRun = current.length > 0 && parentOf(entry) === parentOf(current[0])
    if (colour && (current.length === 0 || continuesRun)) {
      current.push(entry)
      continue
    }
    if (current.length > 1) runs.push(current)
    current = colour ? [entry] : []
  }
  if (current.length > 1) runs.push(current)
  return runs
}
