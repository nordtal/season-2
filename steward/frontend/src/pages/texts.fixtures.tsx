import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen } from "@testing-library/react"
import { vi } from "vitest"

import { Texts } from "@/pages/texts"
import type {
  CommandRun,
  MessageEntry,
  MessageFallback,
  MessageProblem,
  MessageSaveResult,
  MessageSurface,
  MessageText,
  MessageTexts,
} from "@/lib/api"
import { asTextArea } from "@/lib/test-elements"

/** The fake backend, the texts and the helpers the Texts page tests share. */

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
  return { writable: true, texts, languages: [], colours: {}, places: PLACES, ...over }
}

/** What the editor asks besides the texts; a test answers any of them otherwise. */
export type Around = {
  fallbacks?: MessageFallback[]
  check?: (text: string) => MessageProblem[]
  /** Answers a preview's body with the request's name. */
  preview?: (body: unknown) => string
  /** What became of each request, by name. */
  commands?: Record<string, CommandRun>
}

/** `/api/messages` answers `first`, then what the last save left; its PUT, if the test expects one, what `put` makes of the body. */
export function backend(first: MessageTexts, put?: (body: unknown) => Partial<MessageSaveResult>, around: Around = {}) {
  let texts = first
  return vi.fn<(url: string, init?: { method?: string; body?: string }) => Promise<Response>>(async (url, init) => {
    if (url === "/api/messages" && init?.method === "PUT") {
      if (!put) throw new Error("the page saved, which this test did not expect")
      const saved = { texts, warnings: [], ...put(JSON.parse(init.body ?? "")) }
      texts = saved.texts
      return json(saved)
    }
    if (url === "/api/messages") return json(texts)
    if (url === "/api/message-syntax") return json({ tones: { good: "#8ba888" }, kinds: { duration: ["short"] } })
    if (url === "/api/message-examples") return json({})
    if (url === "/api/message-fallbacks") return json(around.fallbacks ?? [])
    if (url === "/glyphs/manifest.json") return json([])
    if (url.startsWith("/api/message-check?")) {
      return json(around.check?.(new URL(url, "http://steward").searchParams.get("text") ?? "") ?? [])
    }
    if (url === "/api/message-preview" && around.preview) {
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
  return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>)
}

/** The page as its route draws it, with `?service=` kept in state instead of the URL. */
export function Page({ service: first }: { service?: string }) {
  const [service, setService] = useState(first)
  return <Texts service={service} onService={setService} />
}

export async function open(label: string | RegExp) {
  fireEvent.click(await screen.findByRole("button", { name: label }))
}

/** A key is a row until it is opened; its field only exists once it is. */
export async function openKey(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }))
}

/** Switches the open key to its source, B, and hands back the field `label` names. */
export async function source(label: string): Promise<HTMLTextAreaElement> {
  fireEvent.click(await screen.findByRole("button", { name: "Source" }))
  return asTextArea(await screen.findByRole("textbox", { name: label }))
}
