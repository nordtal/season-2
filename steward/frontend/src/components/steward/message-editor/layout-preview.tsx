import type { ReactNode } from "react"
import { ArrowSquareOutIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import type { Layout, LayoutElement, LayoutLine, LayoutPart, Tier } from "@/lib/api"
import { euros } from "@/lib/format"
import { ENGLISH, type Language } from "@/lib/message-text"
import type { Run } from "@/lib/rich-text"
import type { Fill } from "@/components/steward/message-editor/examples"
import {
  DiscordButton,
  DiscordMessage,
  DiscordModal,
  DiscordRuns,
  DiscordSelect,
} from "@/components/steward/message-editor/preview"

/** One text as a preview draws it: its runs with the cases its examples pick, or its source when it cannot be read. */
export type DrawnText = { runs: Run[] | null; text: string; fill: Fill }

/** Draws the text `id`, a `bundle/key`, in `language`; `values` fill in before the examples. */
export type DrawText = (id: string, language: Language, values?: Record<string, string>) => DrawnText | null

type Context = {
  layout: Layout
  languages: readonly Language[]
  tiers: readonly Tier[]
  draw: DrawText
  picked: string | undefined
  onPick: ((id: string) => void) | undefined
}

/**
 * A Discord layout as the bot posts it, or as its modal when it has fields, filled with the live tiers and languages and else with the examples. Each text is a button that picks it.
 */
export function DiscordLayoutPreview({
  layout,
  language = ENGLISH,
  languages,
  tiers,
  draw,
  picked,
  onPick,
  className,
}: Omit<Context, "picked" | "onPick"> & {
  language?: Language
  picked?: string
  onPick?: (id: string) => void
  className?: string
}) {
  const context: Context = { layout, languages, tiers, draw, picked, onPick }
  const Frame = layout.elements.some((element) => element.kind === "INPUT") ? DiscordModal : DiscordMessage
  return (
    <Frame className={className}>
      <div className="mt-1 flex max-w-[480px] flex-col gap-2">
        {layout.parts.map((part, index) => (
          <PartView key={index} part={part} language={language} context={context} />
        ))}
      </div>
    </Frame>
  )
}

function PartView({ part, language, context }: { part: LayoutPart; language: Language; context: Context }) {
  const inner = (parts: LayoutPart[] | undefined, at: Language = language) =>
    (parts ?? []).map((child, index) => <PartView key={index} part={child} language={at} context={context} />)
  switch (part.type) {
    case "CONTAINER":
      return (
        <div
          className="flex flex-col gap-2 rounded-[8px] border-l-4 p-4"
          style={{ background: "#2b2d31", borderColor: part.accent ?? "transparent" }}
        >
          {inner(part.parts)}
        </div>
      )
    case "TEXT":
      return <TextBlock lines={part.lines ?? []} language={language} context={context} />
    case "SECTION": {
      const button = part.element === undefined ? undefined : elementOf(context.layout, part.element)
      return (
        <div className="flex items-start gap-3">
          <div className="flex min-w-0 flex-1 flex-col gap-2">{inner(part.parts)}</div>
          {button ? (
            <ElementView element={button} language={language} context={context} />
          ) : (
            <Media address={part.media?.[0]} className="size-20 shrink-0" />
          )}
        </div>
      )
    }
    case "SEPARATOR":
      return <hr className="my-1 border-t" style={{ borderColor: "#3f4147" }} />
    case "GALLERY":
      return (
        <div className={cn("grid grid-cols-1 gap-1", (part.media?.length ?? 0) > 1 && "grid-cols-2")}>
          {(part.media ?? []).map((address) => (
            <Media key={address} address={address} className="h-28 w-full" />
          ))}
        </div>
      )
    case "ROW":
      return (
        <div className="flex flex-wrap gap-2">
          {(part.elements ?? []).map((id) => {
            const element = elementOf(context.layout, id)
            return element ? <ElementView key={id} element={element} language={language} context={context} /> : null
          })}
        </div>
      )
    case "LANGUAGES":
      return (
        <>{(context.languages.length > 0 ? context.languages : [ENGLISH]).map((each) => inner(part.parts, each))}</>
      )
    default:
      return null
  }
}

function elementOf(layout: Layout, id: string): LayoutElement | undefined {
  return layout.elements.find((element) => element.id === id)
}

function TextBlock({ lines, language, context }: { lines: LayoutLine[]; language: Language; context: Context }) {
  return (
    <div className="flex flex-col whitespace-pre-wrap">
      {lines.map((line, index) => {
        const style = cn(
          line.style === "TITLE" && "text-xl leading-snug font-bold text-white",
          line.style === "HEADING" && "font-bold text-white",
        )
        if (line.list !== undefined) {
          const list = elementOf(context.layout, line.list)
          const key = list?.keys[0]
          return key === undefined
            ? null
            : itemsOf(list, context.tiers).map((values, item) => (
                <Pick key={`${index}/${item}`} id={key} context={context}>
                  <Words id={key} language={language} values={values} context={context} />
                </Pick>
              ))
        }
        if (line.texts) {
          return (
            <div key={index} className={style}>
              {line.texts[language] ?? line.texts[ENGLISH] ?? Object.values(line.texts)[0]}
            </div>
          )
        }
        if (line.key === undefined) return null
        return (
          <Pick key={index} id={line.key} context={context} className={style}>
            <Words id={line.key} language={language} context={context} />
          </Pick>
        )
      })}
    </div>
  )
}

/**
 * Returns the items of a data list, each the values of its line. The tiers fill the list the bot fills with them.
 */
function itemsOf(list: LayoutElement | undefined, tiers: readonly Tier[]): Array<Record<string, string> | undefined> {
  // shortcut: only the tiers list has live items, by its id; a further data list shows one item of examples until its data reaches the layouts answer.
  if (list?.id === "tiers" && tiers.length > 0) {
    return tiers.map((tier) => ({ days: String(tier.days), price: euros(tier.priceCents) }))
  }
  return [undefined]
}

function ElementView({ element, language, context }: { element: LayoutElement; language: Language; context: Context }) {
  const key = element.keys[0]
  switch (element.kind) {
    case "BUTTON":
      return key === undefined ? null : (
        <Pick id={key} context={context} inline>
          <DiscordButton>
            <Words id={key} language={language} context={context} />
          </DiscordButton>
        </Pick>
      )
    case "SELECT":
      return key === undefined ? null : (
        <Pick id={key} context={context} className="w-full">
          <DiscordSelect>
            <Words id={key} language={language} context={context} />
          </DiscordSelect>
        </Pick>
      )
    case "INPUT":
      return key === undefined ? null : (
        <div className="flex w-full flex-col gap-2">
          <Pick id={key} context={context} className="font-semibold text-white">
            <Words id={key} language={language} context={context} />
          </Pick>
          <div
            className="flex h-10 items-center rounded-[3px] px-3"
            style={{ background: "#1e1f22", color: "#949ba4" }}
          >
            {element.keys[1] === undefined ? null : (
              <Pick id={element.keys[1]} context={context}>
                <Words id={element.keys[1]} language={language} context={context} />
              </Pick>
            )}
          </div>
        </div>
      )
    case "LINK":
      return (
        <DiscordButton link>
          {element.labels?.[language] ?? element.labels?.[ENGLISH]}
          <ArrowSquareOutIcon aria-hidden className="size-4" />
        </DiscordButton>
      )
    default:
      return null
  }
}

function Words({
  id,
  language,
  values,
  context,
}: {
  id: string
  language: Language
  values?: Record<string, string>
  context: Context
}) {
  const drawn = context.draw(id, language, values)
  if (!drawn) return <span className="font-mono text-xs">{id}</span>
  return drawn.runs ? <DiscordRuns runs={drawn.runs} fill={drawn.fill} /> : <>{drawn.text}</>
}

/** A picked text is outlined; without `onPick` a text is not a button. */
function Pick({
  id,
  context,
  className,
  inline = false,
  children,
}: {
  id: string
  context: Context
  className?: string
  inline?: boolean
  children: ReactNode
}) {
  if (!context.onPick) return <div className={cn(inline && "inline-flex", className)}>{children}</div>
  const onPick = context.onPick
  const picked = context.picked === id
  return (
    <button
      type="button"
      aria-pressed={picked}
      onClick={() => onPick(id)}
      className={cn(
        "rounded-[4px] text-left outline-offset-2 hover:outline-1 hover:outline-[#5865f2] hover:outline-solid",
        inline ? "inline-flex" : "block w-full",
        picked && "outline-2 outline-[#5865f2] outline-solid",
        className,
      )}
    >
      {children}
    </button>
  )
}

/**
 * An image of the layout: a stored upload, an address, or a file the bot's jar packs, which Steward cannot read and draws as its name.
 */
function Media({ address, className }: { address: string | undefined; className?: string }) {
  if (address === undefined) return null
  const upload = /^upload:\/\/(\d+)$/.exec(address)
  if (upload || address.startsWith("https://")) {
    return (
      <img
        src={upload ? `/api/layout-images/${upload[1]}` : address}
        alt=""
        className={cn("rounded-[4px] object-cover", className)}
      />
    )
  }
  return (
    <div
      className={cn("flex items-center justify-center rounded-[4px] font-mono text-xs", className)}
      style={{ background: "#1e1f22", color: "#949ba4" }}
    >
      {address.replace(/^attachment:\/\//, "")}
    </div>
  )
}
