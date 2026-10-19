import { ArrowSquareOutIcon } from "@phosphor-icons/react"
import { cn } from "cn"
import type { ReactNode } from "react"

import type { Document, DocumentDeclaration, MenuDrawing } from "@/lib/api"
import { useDocumentDrawing, useGlyphs, useMessageSyntax } from "@/lib/queries"
import { ENGLISH, type Language } from "@/lib/message-text"
import { markdownRuns, parse, textRuns } from "@/lib/rich-text"
import { t } from "@/lib/texts"
import { useDebounced } from "@/lib/use-debounced"
import { QueryState, Skeleton } from "@/components/steward/query-state"
import {
  DiscordButton,
  DiscordMessage,
  DiscordModal,
  DiscordRuns,
  DiscordSelect,
  McTooltip,
  MinecraftText,
  Preview,
  SCREEN_WRAP,
} from "@/components/steward/message-editor/preview"

/** How long typing pauses before the server draws the draft again. */
export const PAUSE_MS = 300

/**
 * A Discord document as the bot draws it, or as its modal, or a game text as the game draws it. The server draws it with the
 * bot's or the game's own renderer, every value at its declared example; this only draws what comes back.
 */
export function DocumentPreview({
  declaration,
  game,
  document,
  language = ENGLISH,
  className,
}: {
  declaration: DocumentDeclaration
  /** Whether the game draws it, as its view says. */
  game: boolean
  document: Document
  language?: Language
  className?: string
}) {
  const draft = useDebounced(document, PAUSE_MS)
  const drawing = useDocumentDrawing(declaration, draft, language)
  const Frame = declaration.shape === "MODAL" ? DiscordModal : DiscordMessage
  if (declaration.shape === "MENU") {
    return (
      <QueryState query={drawing}>
        {(drawn) =>
          drawn?.menu ? <MenuPreview menu={drawn.menu} className={className} /> : <Skeleton className="h-32 w-full" />
        }
      </QueryState>
    )
  }
  if (game) {
    return (
      <QueryState query={drawing}>
        {(drawn) =>
          drawn?.game ? (
            <GamePreview shape={declaration.shape} lines={drawn.game} className={className} />
          ) : (
            <Skeleton className="h-16 w-full" />
          )
        }
      </QueryState>
    )
  }
  return (
    <QueryState query={drawing}>
      {(drawn) => (
        <Frame className={className}>
          <div className="mt-1 flex max-w-[480px] flex-col gap-2">
            {drawn === undefined ? (
              <Skeleton className="h-16 w-full" />
            ) : (
              <Drawn shape={declaration.shape} discord={drawn.discord ?? {}} language={language} />
            )}
          </div>
        </Frame>
      )}
    </QueryState>
  )
}

const unfilled = (name: string) => `{${name}}`

/**
 * A menu's title on the window's grey, then each item as its tooltip and each label as the window paints it, under the
 * slot the code places it in.
 */
function MenuPreview({ menu, className }: { menu: MenuDrawing; className?: string }) {
  const tones = useMessageSyntax().data?.tones ?? {}
  const glyphs = useGlyphs().data ?? []
  const runs = (source: string) => parse(source, "MINIMESSAGE", Object.keys(tones)) ?? textRuns(source, false)
  return (
    <div
      className={cn("flex min-w-0 flex-col gap-3 overflow-x-auto rounded-md p-3", className)}
      style={{ background: "#C6C6C6" }}
    >
      <div>
        <MinecraftText
          runs={runs(menu.title)}
          fill={unfilled}
          glyphs={glyphs}
          tones={tones}
          base="#404040"
          shadow={false}
        />
      </div>
      <ul className="flex flex-wrap items-start gap-3">
        {menu.parts.map((part, index) => (
          <li key={index} className="flex max-w-full flex-col gap-1">
            <span className="font-mono text-[11px] text-[#404040]">{part.slot}</span>
            {part.type === "ITEM" ? (
              <McTooltip>
                {part.lines.map((line, at) => (
                  <MinecraftText
                    key={at}
                    runs={runs(line)}
                    fill={unfilled}
                    glyphs={glyphs}
                    tones={tones}
                    wrap={SCREEN_WRAP}
                  />
                ))}
              </McTooltip>
            ) : (
              <span className="self-start rounded-[2px] bg-[#8B8B8B] px-2 py-1 font-mono text-xs text-white">
                {part.lines[0] ?? ""}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** A chat text, a tab list or a kick screen on the scene the game draws it in, as a text of a bundle is. */
function GamePreview({ shape, lines, className }: { shape: string; lines: string[]; className?: string }) {
  const tones = useMessageSyntax().data?.tones ?? {}
  const glyphs = useGlyphs().data ?? []
  const source = lines.join("\n")
  return (
    <Preview
      runs={parse(source, "MINIMESSAGE", Object.keys(tones)) ?? textRuns(source, false)}
      shown={shape}
      surface="GAME"
      fill={unfilled}
      glyphs={glyphs}
      tones={tones}
      className={className}
    />
  )
}

/** A component of Discord's JSON, by the fields this preview draws. */
type Component = {
  type: number
  components?: Component[]
  component?: Component
  accessory?: Component
  content?: string
  accent_color?: number
  label?: string
  placeholder?: string
  url?: string
  media?: { url: string }
  file?: { url: string }
  items?: { media: { url: string } }[]
}

/** Discord's numbers for its component types. */
const ROW = 1
const BUTTON = 2
const STRING_SELECT = 3
const TEXT_INPUT = 4
const USER_SELECT = 5
const SECTION = 9
const TEXT = 10
const THUMBNAIL = 11
const GALLERY = 12
const FILE = 13
const SEPARATOR = 14
const CONTAINER = 17
const LABEL = 18

function Drawn({
  shape,
  discord,
  language,
}: {
  shape: DocumentDeclaration["shape"]
  discord: Record<string, unknown>
  language: Language
}) {
  switch (shape) {
    case "MODAL":
      return (
        <>
          <div className="text-xl leading-snug font-bold text-white">{textOf(discord.title)}</div>
          <Components components={componentsOf(discord.components)} language={language} />
        </>
      )
    case "PLAIN":
      return <Markdown text={textOf(discord.content)} language={language} />
    case "CHANNEL_NAME":
      return <span className="font-medium text-white">{textOf(discord.name)}</span>
    default:
      return <Components components={componentsOf(discord.components)} language={language} />
  }
}

function textOf(value: unknown): string {
  return typeof value === "string" ? value : ""
}

function componentsOf(value: unknown): Component[] {
  return Array.isArray(value) ? value : []
}

function Components({ components, language }: { components: Component[] | undefined; language: Language }) {
  return (
    <>
      {(components ?? []).map((component, index) => (
        <ComponentView key={index} component={component} language={language} />
      ))}
    </>
  )
}

function ComponentView({ component, language }: { component: Component; language: Language }): ReactNode {
  switch (component.type) {
    case CONTAINER:
      return (
        <div
          className="flex flex-col gap-2 rounded-[8px] border-l-4 p-4"
          style={{
            background: "#2b2d31",
            borderColor:
              component.accent_color === undefined
                ? "transparent"
                : `#${component.accent_color.toString(16).padStart(6, "0")}`,
          }}
        >
          <Components components={component.components} language={language} />
        </div>
      )
    case TEXT:
      return <Markdown text={component.content ?? ""} language={language} />
    case SECTION:
      return (
        <div className="flex items-start gap-3">
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <Components components={component.components} language={language} />
          </div>
          {component.accessory ? <ComponentView component={component.accessory} language={language} /> : null}
        </div>
      )
    case THUMBNAIL:
      return <Media url={component.media?.url} className="size-20 shrink-0" />
    case GALLERY:
      return (
        <div className={cn("grid grid-cols-1 gap-1", (component.items?.length ?? 0) > 1 && "grid-cols-2")}>
          {(component.items ?? []).map((item, index) => (
            <Media key={index} url={item.media.url} className="h-28 w-full" />
          ))}
        </div>
      )
    case FILE:
      return <Media url={component.file?.url} className="h-12 w-full max-w-xs" />
    case SEPARATOR:
      return <hr className="my-1 border-t" style={{ borderColor: "#3f4147" }} />
    case ROW:
      return (
        <div className="flex flex-wrap gap-2">
          <Components components={component.components} language={language} />
        </div>
      )
    case BUTTON:
      return (
        <DiscordButton link={component.url !== undefined}>
          {component.label}
          {component.url !== undefined ? <ArrowSquareOutIcon aria-hidden className="size-4" /> : null}
        </DiscordButton>
      )
    case STRING_SELECT:
    case USER_SELECT:
      return <DiscordSelect className="w-full">{component.placeholder}</DiscordSelect>
    case LABEL:
      return (
        <div className="flex w-full flex-col gap-2">
          <div className="font-semibold text-white">{component.label}</div>
          {component.component ? <ComponentView component={component.component} language={language} /> : null}
        </div>
      )
    case TEXT_INPUT:
      return (
        <div className="flex h-10 items-center rounded-[3px] px-3" style={{ background: "#1e1f22", color: "#949ba4" }}>
          {component.placeholder}
        </div>
      )
    default:
      return null
  }
}

/** A heading's size by its number of `#`, and `-#` for Discord's small grey line. */
const HEADINGS: [string, string][] = [
  ["### ", "font-bold text-white"],
  ["## ", "text-xl leading-snug font-bold text-white"],
  ["# ", "text-2xl leading-snug font-bold text-white"],
  ["-# ", "text-xs text-[#949ba4]"],
]

/** Discord's markdown as a channel shows it, line by line, with its times and members. */
function Markdown({ text, language }: { text: string; language: Language }) {
  return (
    <div className="flex flex-col whitespace-pre-wrap">
      {text.split("\n").map((line, index) => {
        const heading = HEADINGS.find(([marker]) => line.startsWith(marker))
        const body = heading ? line.slice(heading[0].length) : line
        return (
          <div key={index} className={heading?.[1]}>
            {body === "" ? <br /> : <Inline text={body} language={language} />}
          </div>
        )
      })}
    </div>
  )
}

/** Splits out Discord's `<t:seconds:style>` and `<@id>`, which markdown leaves as they are. */
const TOKEN = /<t:(-?\d+)(?::([tTdDfFR]))?>|<@!?(\d+)>/g

function Inline({ text, language }: { text: string; language: Language }) {
  const parts: ReactNode[] = []
  let at = 0
  for (const match of text.matchAll(TOKEN)) {
    parts.push(<Runs key={parts.length} text={text.slice(at, match.index)} />)
    parts.push(
      match[3] === undefined ? (
        <span key={parts.length} className="rounded-[3px] px-0.5" style={{ background: "#3f4147" }}>
          {timeOf(Number(match[1]), match[2], language)}
        </span>
      ) : (
        <span
          key={parts.length}
          className="rounded-[3px] px-0.5 font-medium"
          style={{ background: "rgba(88,101,242,0.3)", color: "#c9cdfb" }}
        >
          {t("steward.message-editor.member")}
        </span>
      ),
    )
    at = match.index + match[0].length
  }
  parts.push(<Runs key={parts.length} text={text.slice(at)} />)
  return <>{parts}</>
}

function Runs({ text }: { text: string }) {
  return text === "" ? null : <DiscordRuns runs={markdownRuns(text)} fill={(name) => name} />
}

/** A time as Discord shows it to a reader of `language`, in the reader's own zone. */
function timeOf(seconds: number, style: string | undefined, language: Language): string {
  const date = new Date(seconds * 1000)
  if (style === "R") {
    const days = Math.round((seconds * 1000 - Date.now()) / 86_400_000)
    return new Intl.RelativeTimeFormat(language, { numeric: "auto" }).format(days, "day")
  }
  const shapes: Record<string, Intl.DateTimeFormatOptions> = {
    t: { timeStyle: "short" },
    T: { timeStyle: "medium" },
    d: { dateStyle: "short" },
    D: { dateStyle: "long" },
    F: { dateStyle: "full", timeStyle: "short" },
  }
  return date.toLocaleString(language, shapes[style ?? "f"] ?? { dateStyle: "long", timeStyle: "short" })
}

/**
 * An image of the document: a stored upload, an address, or a file the bot's jar packs, which Steward cannot read and draws as its name.
 */
export function Media({ url, className }: { url: string | undefined; className?: string }) {
  if (url === undefined) return null
  // The bot packs a stored upload as an attachment, so a drawn file names it behind attachment://.
  const upload = /^(?:attachment:\/\/)?upload:\/\/(\d+)$/.exec(url)
  if (upload || url.startsWith("https://")) {
    return (
      <img
        src={upload ? `/api/document-images/${upload[1]}` : url}
        alt=""
        className={cn("rounded-[4px] object-cover", className)}
      />
    )
  }
  return (
    <div
      className={cn(
        "flex items-center justify-center overflow-hidden rounded-[4px] p-1 text-center font-mono text-xs break-all",
        className,
      )}
      style={{ background: "#1e1f22", color: "#949ba4" }}
    >
      {url.replace(/^attachment:\/\//, "")}
    </div>
  )
}
