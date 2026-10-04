import { useState } from "react"
import { PaletteIcon, PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import { NAMED_COLOURS, gradientAt, hexOf } from "@/lib/rich-text"
import type { Style } from "@/lib/rich-text"
import { t } from "@/lib/texts"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import type { Tones } from "@/components/steward/message-editor/preview"
import { MenuTrigger } from "@/components/steward/message-editor/tools"

/** Minecraft's sixteen colours in the order of their old section codes, 0 to f. */
const COLOUR_ORDER = [
  "black",
  "dark_blue",
  "dark_green",
  "dark_aqua",
  "dark_red",
  "dark_purple",
  "gold",
  "gray",
  "dark_gray",
  "blue",
  "green",
  "aqua",
  "red",
  "light_purple",
  "yellow",
  "white",
]

/** The colour a style is drawn in, as a small swatch: a gradient shows as one. */
export function Swatch({ style, tones, className }: { style: Style; tones: Tones; className?: string }) {
  const background = style.gradient
    ? `linear-gradient(90deg, ${style.gradient.map(hexOf).join(", ")})`
    : style.colour
      ? hexOf(style.colour)
      : style.tone
        ? tones[style.tone]
        : undefined
  return (
    <span
      aria-hidden
      className={cn("inline-block size-4 shrink-0 rounded-sm border border-border", className)}
      style={background ? { background } : undefined}
    />
  )
}

type ColourMode = "tone" | "colour" | "gradient"

/** A tone first, which follows the palette; a colour or a gradient of its own only where the text may have one. */
export function ColourMenu({
  style,
  tones,
  onChange,
  disabled,
}: {
  style: Style
  tones: Tones
  onChange: (patch: Pick<Style, "tone" | "colour" | "gradient">) => void
  disabled?: boolean
}) {
  const [mode, setMode] = useState<ColourMode>(style.gradient ? "gradient" : style.colour ? "colour" : "tone")
  const stops = style.gradient ?? [style.colour ?? "#4a63d8", "#ffffff"]
  const setStops = (next: string[]) => onChange({ gradient: next, colour: undefined, tone: undefined })
  const coloured = Boolean(style.tone || style.colour || style.gradient)
  return (
    <Popover>
      <PopoverTrigger asChild>
        <MenuTrigger label={t("steward.message-editor.colour")} disabled={disabled}>
          {coloured ? <Swatch style={style} tones={tones} /> : <PaletteIcon aria-hidden />}
        </MenuTrigger>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="flex w-[min(18rem,calc(100vw-2rem))] flex-col gap-3"
        onOpenAutoFocus={(event) => event.preventDefault()}
      >
        <Tabs
          value={mode}
          onValueChange={(value) => {
            if (value === "tone" || value === "colour" || value === "gradient") setMode(value)
          }}
        >
          <TabsList className="w-full">
            <TabsTrigger value="tone">{t("steward.message-editor.tone")}</TabsTrigger>
            <TabsTrigger value="colour">{t("steward.message-editor.colour")}</TabsTrigger>
            <TabsTrigger value="gradient">{t("steward.message-editor.gradient")}</TabsTrigger>
          </TabsList>
        </Tabs>
        {mode === "tone" ? (
          <div className="grid grid-cols-3 gap-1">
            {Object.entries(tones).map(([tone, hex]) => (
              <button
                key={tone}
                type="button"
                aria-pressed={style.tone === tone}
                onClick={() => onChange({ tone, colour: undefined, gradient: undefined })}
                className={cn(
                  "flex min-h-control min-w-0 items-center gap-1.5 rounded-md px-1.5 text-left text-xs hover:bg-accent",
                  style.tone === tone && "bg-accent",
                )}
              >
                <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ background: hex }} />
                <span className="truncate">{tone}</span>
              </button>
            ))}
          </div>
        ) : mode === "colour" ? (
          <>
            <div className="grid grid-cols-8 gap-1">
              {COLOUR_ORDER.map((name) => (
                <button
                  key={name}
                  type="button"
                  aria-label={name}
                  aria-pressed={style.colour === name}
                  onClick={() => onChange({ colour: name, gradient: undefined, tone: undefined })}
                  className={cn(
                    "aspect-square rounded-sm border border-border",
                    style.colour === name && "ring-2 ring-ring ring-offset-1 ring-offset-popover",
                  )}
                  style={{ background: NAMED_COLOURS[name] }}
                />
              ))}
            </div>
            <HexField
              value={style.colour ? hexOf(style.colour) : ""}
              onChange={(hex) => onChange({ colour: hex, gradient: undefined, tone: undefined })}
            />
          </>
        ) : (
          <>
            <div
              className="h-4 rounded-sm"
              style={{
                background: `linear-gradient(90deg, ${stops.map((_, at) => gradientAt(stops, at / (stops.length - 1))).join(", ")})`,
              }}
            />
            {stops.map((stop, at) => (
              <div key={at} className="flex items-center gap-2">
                <HexField
                  value={hexOf(stop)}
                  onChange={(hex) => setStops(stops.map((old, index) => (index === at ? hex : old)))}
                />
                <Button
                  type="button"
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("steward.message-editor.remove-colour")}
                  disabled={stops.length <= 2}
                  onClick={() => setStops(stops.filter((_, index) => index !== at))}
                >
                  <TrashIcon aria-hidden />
                </Button>
              </div>
            ))}
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              aria-label={t("steward.message-editor.add-colour")}
              className="self-start"
              onClick={() => setStops([...stops, stops[stops.length - 1]])}
            >
              <PlusIcon aria-hidden />
            </Button>
          </>
        )}
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={!coloured}
          onClick={() => onChange({ tone: undefined, colour: undefined, gradient: undefined })}
        >
          {t("steward.message-editor.no-colour")}
        </Button>
      </PopoverContent>
    </Popover>
  )
}

function HexField({ value, onChange }: { value: string; onChange: (hex: string) => void }) {
  const [typed, setTyped] = useState<string | null>(null)
  return (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      <input
        type="color"
        aria-label={t("steward.settings.pick-colour")}
        value={value || "#ffffff"}
        onChange={(event) => onChange(event.target.value)}
        className="size-8 shrink-0 cursor-pointer rounded-md border border-border bg-transparent p-0.5"
      />
      <Input
        aria-label={t("steward.message-editor.hex-colour")}
        value={typed ?? value}
        placeholder="#4a63d8"
        spellCheck={false}
        className="min-w-0 font-mono"
        onChange={(event) => {
          setTyped(event.target.value)
          if (/^#[0-9a-fA-F]{6}$/.test(event.target.value)) onChange(event.target.value.toLowerCase())
        }}
        onBlur={() => setTyped(null)}
      />
    </div>
  )
}
