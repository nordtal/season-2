import type { ConfigEntry } from "@/lib/api"
import { Input } from "@/components/ui/input"

/**
 * A hex colour, picked with a wheel or typed by hand, previewed on the background it will actually
 * be read against (steward/63).
 *
 * **Why this exists.** season-2-ingame/22 turned five `private static final` Java constants into
 * settings with a hex value - `#8ba888` and friends, `smp`/`hunger-games`/`limbo`/`network-control`
 * each with their own `colours.yml` - and a bare text field is a worse interface for a colour than
 * for almost anything else this page draws: a wrong role id is still readable as a role id, but a
 * wrong hex string looks like nothing until it is rendered somewhere.
 *
 * **The wheel never replaces the number.** Somebody who already knows the six digits types them,
 * the same way `SnowflakePicker` still takes a pasted snowflake when the guild cannot be listed -
 * the native swatch below is an addition, not a gate in front of the text field.
 *
 * **The preview sits on Minecraft's own chat background, not on this card's.** Till's own case: pick
 * a chat colour on a white field and look at it in game, and all thirteen prestige colours (the
 * other half of this ticket, season-2-ingame/23, not yet shipped) end up looking the same - a light
 * tone reads fine on white and vanishes on what the game actually paints behind it.
 */

/**
 * Minecraft's own chat background - black, not fully opaque.
 *
 * Source: minecraft.wiki, the `Options.txt` page, `textBackgroundOpacity` (default `0.5`, added in
 * 19w11a / 1.14) paired with `backgroundForChatOnly` (default `true`, meaning the opacity setting
 * applies to chat text and nothing else) - checked 2026-09-16, not taken from memory. That is
 * `rgba(0, 0, 0, 0.5)`, not a rounder-looking dark grey guessed to stand in for it.
 */
export const MINECRAFT_CHAT_BACKGROUND = "rgba(0, 0, 0, 0.5)"

const HEX_COLOUR = /^#[0-9a-f]{6}$/i

/** A syntactically valid `#rrggbb`, or `null` while the field holds anything else - including empty,
 * or a value still being typed. Used for both the native swatch (which refuses anything else) and
 * the preview's own colour. */
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
  disabled: boolean
  onChange: (value: string) => void
}) {
  const valid = validHex(value)

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        {/* The browser's own picker - a wheel, an eyedropper on platforms that offer one, and a hex
            field of its own that stays in sync with the text `Input` beside it. It refuses a value
            that is not exactly `#rrggbb`, which is why it falls back to black rather than mirroring
            `value` (mid-edit, or blank) straight through. */}
        <input
          type="color"
          aria-label="Pick a colour"
          disabled={disabled}
          value={valid ?? "#000000"}
          onChange={(event) => onChange(event.target.value)}
          className="h-9 w-9 shrink-0 cursor-pointer rounded border border-input bg-transparent p-0.5 disabled:cursor-not-allowed disabled:opacity-50"
        />
        <Input
          id={id}
          disabled={disabled}
          value={value}
          spellCheck={false}
          placeholder="#rrggbb"
          className="min-w-0 flex-1 font-mono text-sm"
          onChange={(event) => onChange(event.target.value)}
        />
      </div>
      <div
        role="img"
        aria-label={valid ? `Preview on Minecraft's chat background` : "No valid colour to preview yet"}
        style={{ backgroundColor: MINECRAFT_CHAT_BACKGROUND }}
        className="rounded px-2 py-1.5"
      >
        <span
          style={valid ? { color: valid } : undefined}
          className={`font-mono text-sm ${valid ? "" : "text-muted-foreground italic"}`}
        >
          {valid ? "Nordtal — sample chat text" : "type a hex value to preview it"}
        </span>
      </div>
    </div>
  )
}

/**
 * Consecutive scalar entries, sharing a parent, that all look like a colour (steward/63's fourth
 * requirement): what `colourRuns` groups into one row instead of a stack. It is where `colourValue`
 * (`config-controls.tsx`) earns the qualifier in its own name - the four `colours.yml` files this
 * shipped for are exactly five such entries each, in file order, with no heading between them.
 *
 * **`isColour` is a parameter, not an import of `colourValue` itself.** `config-controls.tsx`
 * already imports `ColourControl` from this file to draw a lone colour field; importing
 * `colourValue` back from there would close a cycle between the two, the exact thing `discordId`'s
 * own move to `config-controls.tsx` (steward/57) was to get away from. Taking the predicate as an
 * argument keeps this file knowing nothing about `config-controls.tsx` at all, in either direction.
 *
 * **What breaks it.** A blank member - `isColour` returning `false` for a value it cannot read as
 * `#rrggbb` - splits the run rather than joining it: that key falls back to being drawn as its own
 * ordinary field, not part of the row, and not even offered the picker (see `colourValue`'s own
 * comment on why an empty value is never assumed to be a colour). None of the four files this
 * shipped for hits that today - every default is already a valid hex string - but a colour saved
 * blank by hand would hit it, and the honest answer is: it drops out of the row until it holds a
 * hex value again.
 */
export function colourRuns(
  entries: ConfigEntry[],
  isColour: (entry: ConfigEntry) => boolean,
): ConfigEntry[][] {
  const runs: ConfigEntry[][] = []
  let current: ConfigEntry[] = []

  const parentOf = (entry: ConfigEntry) =>
    entry.path.includes(".") ? entry.path.slice(0, entry.path.lastIndexOf(".")) : ""

  for (const entry of entries) {
    const colour = isColour(entry)
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
