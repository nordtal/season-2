import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { expect, vi } from "vitest"

import { Translations } from "@/pages/translations"
import { TooltipProvider } from "@/components/ui/tooltip"
import type { TranslationsState } from "@/components/steward/translations-view"
import type {
  CommandRun,
  Document,
  DocumentDeclaration,
  DocumentDraft,
  DocumentList,
  DocumentPart,
  DocumentProblem,
  DocumentVersion,
  DocumentView,
  MessageEntry,
  MessageFallback,
  MessageProblem,
  MessageSaveResult,
  MessageSurface,
  MessageText,
  MessageTexts,
  SharedPartChange,
  TranslationHit,
} from "@/lib/api"
import { asTextArea } from "@/lib/test-elements"
import { APPLIED } from "@/lib/query-fixtures"

/** The fake backend, the texts and the helpers the Translations page tests share. */

export function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

/** Every place with where it is, as `/api/messages` names them. */
export const PLACES: Record<string, MessageSurface> = {
  CHAT: "GAME",
  ACTION_BAR: "GAME",
  TITLE: "GAME",
  SUBTITLE: "GAME",
  BOSS_BAR: "GAME",
  TAB_LIST: "GAME",
  GUI: "GAME",
  HOLOGRAM: "GAME",
  KICK_SCREEN: "GAME",
  SERVER_LIST: "GAME",
  DISCORD_MESSAGE: "DISCORD",
  DISCORD_EMBED: "DISCORD",
  DISCORD_EMBED_HEADING: "DISCORD",
  DISCORD_BUTTON: "DISCORD",
  DISCORD_MODAL: "DISCORD",
  DISCORD_SELECT: "DISCORD",
  DISCORD_CHANNEL: "DISCORD",
  DISCORD_COMMAND: "DISCORD",
  STEWARD: "STEWARD",
  PUSH: "STEWARD",
}

export function entry(over: Partial<MessageEntry> & { key: string }): MessageEntry {
  return {
    bundle: "smp",
    inBundle: true,
    texts: {},
    overrides: {},
    args: [],
    section: [],
    shown: ["CHAT"],
    limit: 0,
    ...over,
  }
}

/** One text of the smp jar, shown by smp alone and reached by no preview unless a test says otherwise. */
export function text(over: Partial<MessageEntry> & { key: string }, around: Partial<Omit<MessageText, "entry">> = {}) {
  return { entry: entry(over), path: "smp/smp", services: ["smp"], previews: {}, ...around }
}

export function listing(texts: MessageText[], over: Partial<MessageTexts> = {}): MessageTexts {
  return { texts, languages: [], colours: {}, places: PLACES, ...over }
}

/**
 * A Discord document of the bot, a message of the area Contribution named by its slug unless `over` and `around` say
 * otherwise.
 */
export function view(
  over: Partial<DocumentDeclaration> & { name: string },
  document: Document,
  around: Partial<Omit<DocumentView, "declaration">> = {},
): DocumentView {
  return {
    declaration: {
      namespace: "discord",
      title: over.name,
      area: "Contribution",
      shape: "MESSAGE",
      values: {},
      slots: [],
      ...over,
    },
    services: ["discord-bot"],
    game: false,
    packaged: document,
    hash: "packaged",
    named: {},
    outdated: false,
    adapted: [],
    ...around,
  }
}

export function documents(views: DocumentView[], over: Partial<DocumentList> = {}): DocumentList {
  return { documents: views, languages: ["en"], sharedParts: [], ...over }
}

/** What the editor asks besides the texts; a test answers any of them otherwise. */
export type Around = {
  documents?: DocumentList
  fallbacks?: MessageFallback[]
  check?: (text: string) => MessageProblem[]
  /** Draws a document's draft as the server would, as Discord's JSON. */
  render?: (draft: DocumentDraft) => Record<string, unknown>
  /** What a save of the draft would be refused or warned for. */
  problems?: (draft: DocumentDraft) => DocumentProblem[]
  /** Answers a document's save, at its path under `/api/documents/`, with the list it leaves. */
  saveDocument?: (path: string, document: Document) => DocumentList
  /** Answers the return of a document to its packaged one, at its path under `/api/documents/`. */
  resetDocument?: (path: string) => DocumentList
  /** Answers what saving `part` as shared part `name` would change. */
  sharedPartChanges?: (name: string, part: DocumentPart) => SharedPartChange[]
  /** Answers the save of shared part `name`, or its removal for `null`, with the list it leaves. */
  saveSharedPart?: (name: string, part: DocumentPart | null) => DocumentList
  /** Answers a document's versions by its path under `/api/documents/`. */
  versions?: (path: string) => DocumentVersion[]
  /** Answers the body of a text's or a document's preview with the request's name. */
  preview?: (body: unknown) => string
  /** What became of each request, by name. */
  commands?: Record<string, CommandRun>
  /** Answers the server's translation search, which finds nothing unless a test says otherwise. */
  search?: (query: string) => TranslationHit[]
}

/** `/api/messages` answers `first`, then what the last save left; its PUT, if the test expects one, what `put` makes of the body. */
export function backend(first: MessageTexts, put?: (body: unknown) => Partial<MessageSaveResult>, around: Around = {}) {
  let texts = first
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    if (url === "/api/messages" && init?.method === "PUT") {
      if (!put) throw new Error("the page saved, which this test did not expect")
      const saved = { texts, warnings: [], reload: APPLIED, ...put(JSON.parse(init.body ?? "")) }
      texts = saved.texts
      return json(saved)
    }
    if (url === "/api/messages") return json(texts)
    if (url === "/api/documents") return json(around.documents ?? documents([]))
    if (url === "/api/documents/render" && around.render) {
      const draft: DocumentDraft = JSON.parse(init?.body ?? "")
      return json({ discord: around.render(draft), components: 7, most: 40, problems: around.problems?.(draft) ?? [] })
    }
    if (url.startsWith("/api/documents/") && url.endsWith("/versions") && around.versions) {
      return json(around.versions(url.slice("/api/documents/".length, -"/versions".length)))
    }
    if (url.startsWith("/api/documents/") && init?.method === "DELETE") {
      if (!around.resetDocument) throw new Error("the page reset a document, which this test did not expect")
      return json(around.resetDocument(url.slice("/api/documents/".length)))
    }
    if (url.startsWith("/api/documents/") && init?.method === "PUT") {
      if (!around.saveDocument) throw new Error("the page saved a document, which this test did not expect")
      return json(around.saveDocument(url.slice("/api/documents/".length), JSON.parse(init.body ?? "")))
    }
    if (url.startsWith("/api/shared-parts/") && url.endsWith("/changes") && around.sharedPartChanges) {
      return json(around.sharedPartChanges(url.split("/")[3], JSON.parse(init?.body ?? "")))
    }
    if (url.startsWith("/api/shared-parts/") && (init?.method === "PUT" || init?.method === "DELETE")) {
      if (!around.saveSharedPart) throw new Error("the page saved a shared part, which this test did not expect")
      return json(around.saveSharedPart(url.split("/")[3], init.method === "PUT" ? JSON.parse(init.body ?? "") : null))
    }
    if (url.startsWith("/api/translation-search?")) {
      return json(around.search?.(new URL(url, "http://steward").searchParams.get("query") ?? "") ?? [])
    }
    if (url === "/api/message-syntax") return json({ tones: { good: "#8ba888" }, kinds: { duration: ["short"] } })
    if (url === "/api/message-examples") return json({})
    if (url === "/api/message-fallbacks") return json(around.fallbacks ?? [])
    if (url === "/glyphs/manifest.json") return json([])
    if (url.startsWith("/api/message-check?")) {
      return json(around.check?.(new URL(url, "http://steward").searchParams.get("text") ?? "") ?? [])
    }
    if ((url === "/api/message-preview" || url === "/api/document-preview") && around.preview) {
      return json({ id: around.preview(JSON.parse(init?.body ?? "")), status: "PENDING" })
    }
    const command = url.startsWith("/api/commands/") ? around.commands?.[url.slice("/api/commands/".length)] : null
    if (command) return json(command)
    throw new Error(`the page asked for ${url}, which this test did not expect`)
  })
}

export function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

/** The page as its route draws it, with its search parameters kept in state instead of the URL. */
export function Page({ service: first }: { service?: string }) {
  const [service, setService] = useState(first)
  const [state, setState] = useState<TranslationsState>({})
  return (
    <Translations
      service={service}
      onService={setService}
      state={state}
      onState={(next) => setState((kept) => ({ ...kept, ...next }))}
    />
  )
}

export async function open(label: string | RegExp) {
  fireEvent.click(await screen.findByRole("button", { name: label }))
}

function shown(label: string | RegExp): HTMLElement | undefined {
  return screen.queryAllByRole("button", { name: label })[0]
}

/** Opens the item `name` names from wherever the list stands, through `area`, and hands back the open item. */
export async function openItem(name: string, area: RegExp = /^In game/): Promise<HTMLElement> {
  await waitFor(() => expect(shown(name) ?? shown(area) ?? shown("Areas")).toBeDefined())
  if (!shown(name)) {
    const areas = shown("Areas")
    if (areas) fireEvent.click(areas)
    fireEvent.click(await screen.findByRole("button", { name: area }))
  }
  fireEvent.click((await screen.findAllByRole("button", { name }))[0])
  return screen.findByRole("article", { name })
}

/** Switches the open key to its source, B, and hands back the field `label` names. */
export async function source(label: string): Promise<HTMLTextAreaElement> {
  fireEvent.click(await screen.findByRole("button", { name: "Source" }))
  return asTextArea(await screen.findByRole("textbox", { name: label }))
}
