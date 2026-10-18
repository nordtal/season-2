import { ArrowSquareOutIcon } from "@phosphor-icons/react"
import { cn } from "cn"

import type {
  Document,
  DocumentDeclaration,
  DocumentItem,
  DocumentLine,
  DocumentPart,
  DocumentSlot,
  DocumentValue,
  Tier,
} from "@/lib/api"
import { sourceOf, type DocumentText } from "@/lib/documents"
import { euros } from "@/lib/format"
import { ENGLISH, type Language } from "@/lib/message-text"
import { branchesOf, parse } from "@/lib/rich-text"
import {
  DiscordButton,
  DiscordMessage,
  DiscordModal,
  DiscordRuns,
  DiscordSelect,
} from "@/components/steward/message-editor/preview"

/** Names a value of type message as the example draws it: the text of its example key, or `null` to show the key. */
export type DrawExample = (id: string, language: Language) => string | null

type Context = {
  declaration: DocumentDeclaration
  languages: readonly Language[]
  tiers: readonly Tier[]
  example: DrawExample
}

type Values = Record<string, string>

/**
 * A Discord document as the bot draws it, or as its modal, filled with the live tiers and languages and else with the declared examples.
 */
export function DiscordDocumentPreview({
  declaration,
  document,
  language = ENGLISH,
  languages,
  tiers,
  example,
  className,
}: Omit<Context, never> & {
  document: Document
  language?: Language
  className?: string
}) {
  const context: Context = { declaration, languages, tiers, example }
  const Frame = declaration.shape === "MODAL" ? DiscordModal : DiscordMessage
  return (
    <Frame className={className}>
      <div className="mt-1 flex max-w-[480px] flex-col gap-2">
        <Parts parts={document.parts} language={language} context={context} />
      </div>
    </Frame>
  )
}

function Parts({
  parts,
  language,
  context,
}: {
  parts: DocumentPart[] | undefined
  language: Language
  context: Context
}) {
  return (
    <>
      {(parts ?? [])
        .filter((part) => holds(part.when, context))
        .map((part, index) => (
          <PartView key={index} part={part} language={language} context={context} />
        ))}
    </>
  )
}

function PartView({ part, language, context }: { part: DocumentPart; language: Language; context: Context }) {
  switch (part.type) {
    case "CONTAINER":
      return (
        <div
          className="flex flex-col gap-2 rounded-[8px] border-l-4 p-4"
          style={{ background: "#2b2d31", borderColor: part.accent ?? "transparent" }}
        >
          <Parts parts={part.parts} language={language} context={context} />
        </div>
      )
    case "TEXT":
      return <TextBlock lines={part.lines ?? []} language={language} context={context} />
    case "SECTION":
      return (
        <div className="flex items-start gap-3">
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <Parts parts={part.parts} language={language} context={context} />
          </div>
          {part.button ? (
            <ItemView item={part.button} language={language} context={context} />
          ) : (
            <Media address={part.thumbnail} className="size-20 shrink-0" />
          )}
        </div>
      )
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
          {(part.items ?? [])
            .filter((item) => holds(item.when, context))
            .map((item, index) => (
              <ItemView key={index} item={item} language={language} context={context} />
            ))}
        </div>
      )
    case "LANGUAGES":
      return (
        <>
          {(context.languages.length > 0 ? context.languages : [ENGLISH]).map((each) => (
            <Parts key={each} parts={part.parts} language={each} context={context} />
          ))}
        </>
      )
    default:
      return null
  }
}

function TextBlock({ lines, language, context }: { lines: DocumentLine[]; language: Language; context: Context }) {
  return (
    <div className="flex flex-col whitespace-pre-wrap">
      {lines
        .filter((line) => holds(line.when, context))
        .map((line, index) => {
          const style = cn(
            line.style === "TITLE" && "text-xl leading-snug font-bold text-white",
            line.style === "HEADING" && "font-bold text-white",
          )
          const items = line.list === undefined ? [undefined] : itemsOf(slotOf(context, line.list), context.tiers)
          return items.map((values, item) => (
            <div key={`${index}/${item}`} className={style}>
              <Words text={line.text} language={language} values={values} context={context} />
            </div>
          ))
        })}
    </div>
  )
}

function slotOf(context: Context, id: string | undefined): DocumentSlot | undefined {
  return context.declaration.slots.find((slot) => slot.id === id)
}

/**
 * Returns the items of a data list, each the values of its line. The tiers fill the list the bot fills with them.
 */
function itemsOf(list: DocumentSlot | undefined, tiers: readonly Tier[]): Array<Values | undefined> {
  // shortcut: only the tiers list has live items, by its id; a further data list shows one item of examples until its data reaches the documents answer.
  if (list?.id === "tiers" && tiers.length > 0) {
    return tiers.map((tier) => ({ days: String(tier.days), price: euros(tier.priceCents) }))
  }
  return [
    list ? Object.fromEntries(Object.entries(list.values).map(([name, value]) => [name, exampleOf(value)])) : undefined,
  ]
}

/**
 * Returns whether a `when` holds for the declared examples: `value`, `!value` or `value=constant`. A slot holds, as its example fills it.
 */
function holds(when: string | undefined, context: Context): boolean {
  if (when === undefined) return true
  const match = /^(!?)([a-z][A-Za-z0-9_.]*)(?:=([a-z0-9][a-z0-9-]*))?$/.exec(when)
  if (!match) return false
  const [, negated, name, constant] = match
  if (constant !== undefined) return context.declaration.values[name]?.example === constant
  const example = context.declaration.values[name]?.example
  const truthy =
    example === undefined
      ? slotOf(context, name) !== undefined
      : example !== "" && example !== "false" && !/^-?0+(\.0+)?$/.test(example)
  return negated ? !truthy : truthy
}

function ItemView({ item, language, context }: { item: DocumentItem; language: Language; context: Context }) {
  if (item.link !== undefined) {
    return (
      <DiscordButton link>
        <Words text={item.label} language={language} context={context} />
        <ArrowSquareOutIcon aria-hidden className="size-4" />
      </DiscordButton>
    )
  }
  switch (slotOf(context, item.slot)?.type) {
    case "BUTTON":
      return (
        <DiscordButton>
          <Words text={item.label} language={language} context={context} fallback={item.slot} />
        </DiscordButton>
      )
    case "SELECT":
      return (
        <DiscordSelect className="w-full">
          <Words text={item.placeholder} language={language} context={context} />
        </DiscordSelect>
      )
    case "INPUT":
      return (
        <div className="flex w-full flex-col gap-2">
          <div className="font-semibold text-white">
            <Words text={item.label} language={language} context={context} />
          </div>
          <div
            className="flex h-10 items-center rounded-[3px] px-3"
            style={{ background: "#1e1f22", color: "#949ba4" }}
          >
            <Words text={item.placeholder} language={language} context={context} />
          </div>
        </div>
      )
    default:
      return null
  }
}

/** A value's example as the bot shows it; a sum of money is declared in cents. */
function exampleOf(value: DocumentValue): string {
  return value.kind === "money" ? euros(Number(value.example)) : value.example
}

/** A text in `language` with its values filled in: the item's own, then the declared examples, then the name. */
function Words({
  text,
  language,
  values,
  context,
  fallback,
}: {
  text: DocumentText | undefined
  language: Language
  values?: Values
  context: Context
  fallback?: string
}) {
  const source = sourceOf(text, language)
  if (!source) return fallback === undefined ? null : <span className="font-mono text-xs">{fallback}</span>
  const fill = (name: string) => {
    if (values?.[name] !== undefined) return values[name]
    const value = context.declaration.values[name]
    if (value === undefined) return name
    return (value.kind === "message" ? context.example(value.example, language) : null) ?? exampleOf(value)
  }
  const runs = parse(source, "DISCORD_MARKDOWN", [])
  return runs ? <DiscordRuns runs={branchesOf(runs, "DISCORD_MARKDOWN", [], fill)} fill={fill} /> : <>{source}</>
}

/**
 * An image of the document: a stored upload, an address, or a file the bot's jar packs, which Steward cannot read and draws as its name.
 */
function Media({ address, className }: { address: string | undefined; className?: string }) {
  if (address === undefined) return null
  const upload = /^upload:\/\/(\d+)$/.exec(address)
  if (upload || address.startsWith("https://")) {
    return (
      <img
        src={upload ? `/api/document-images/${upload[1]}` : address}
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
