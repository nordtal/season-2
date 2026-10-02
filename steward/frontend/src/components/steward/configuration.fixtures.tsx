import { useState, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen } from "@testing-library/react"
import { vi } from "vitest"

import { ServiceSettings } from "@/components/steward/settings"
import type { ConfigEntry, ConfigLocation } from "@/lib/api"
import { TooltipProvider } from "@/components/ui/tooltip"

/** The fake backend, the entries and the helpers the configuration form tests share. */

export async function search(query: string) {
  fireEvent.change(await screen.findByLabelText("Search this file"), { target: { value: query } })
}

export function asInput(element: HTMLElement): HTMLInputElement {
  if (!(element instanceof HTMLInputElement)) throw new Error("expected an input element")
  return element
}

/** The text a `PUT` request carried, since `RequestInit['body']` is not always a string. */
export function requestBody(body: BodyInit | null | undefined): string {
  if (typeof body !== "string") throw new Error("expected the request body to be a string")
  return body
}

export function isBodyWithChanges(value: unknown): value is { changes: unknown } {
  return typeof value === "object" && value !== null && "changes" in value
}

export function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

export function location(over: Partial<ConfigLocation> & { path: string; name: string }): ConfigLocation {
  return {
    service: "steward",
    label: "",
    live: true,
    readable: true,
    writable: true,
    ...over,
  }
}

export function entry(over: Partial<ConfigEntry> & { path: string; key: string }): ConfigEntry {
  return {
    label: over.key,
    explanation: "",
    noExplanationNeeded: false,
    filled: true,
    value: "",
    items: [],
    kind: "SCALAR",
    type: "STRING",
    editable: true,
    secret: false,
    environmentOverridden: false,
    ...over,
  }
}

export function colourEntry(key: string, value: string): ConfigEntry {
  return entry({ path: key, key, label: key, value })
}

export function ladder(hours: string[], colours: string[]): ConfigEntry[] {
  const keys = ["tier-01", "tier-02", "tier-03"]
  return [
    entry({ path: "admin", key: "admin", label: "admin", value: "#ff5555" }),
    entry({ path: "hours", key: "hours", label: "hours", kind: "MAP", editable: false }),
    ...keys.map((key, at) => entry({ path: `hours.${key}`, key, label: key, type: "INTEGER", value: hours[at] })),
    entry({ path: "colours", key: "colours", label: "colours", kind: "MAP", editable: false }),
    ...keys.map((key, at) => entry({ path: `colours.${key}`, key, label: key, value: colours[at] })),
  ]
}

/** The text on screen, without the `sr-only` parts. */
export function visibleText(element: HTMLElement): string {
  const copy = element.cloneNode(true)
  if (!(copy instanceof HTMLElement)) throw new Error("expected a cloned element")
  copy.querySelectorAll(".sr-only").forEach((hidden) => hidden.remove())
  return copy.textContent ?? ""
}

/** A field by id, its entry's path, since a colour shows its value twice. */
export function fieldFor(container: HTMLElement, path: string): HTMLInputElement {
  return asInput(nonNull(container.querySelector<HTMLElement>(`[id="${path}"]`), `a field drawn for ${path}`))
}

export function nonNull<T>(value: T | null, what: string): T {
  if (value === null) throw new Error(`expected ${what}`)
  return value
}

export const GUILD_UNAVAILABLE = { available: false, reason: "no bot token in this test", entries: [] }

/** One `/api/setting-groups/<path>` answer per fixture file, keyed the way the route is called. */
export function backend(documents: Record<string, ConfigLocation & Record<string, unknown>>) {
  const listing = Object.values(documents).map(({ service, name, path, readable, writable }) => ({
    service,
    name,
    path,
    readable,
    writable,
  }))
  return vi.fn<(url: string) => Promise<Response>>(async (url: string) => {
    if (url === "/api/setting-groups") return json(listing)
    if (url === "/api/messages") return json([])
    if (url === "/api/discord/roles" || url === "/api/discord/channels") return json(GUILD_UNAVAILABLE)
    const found = Object.entries(documents).find(([path]) => url === `/api/setting-groups/${path}`)
    if (found) return json(found[1])
    throw new Error(`the form asked for ${url}, which this test did not expect`)
  })
}

export function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  /** The tooltip provider the Shell supplies, which EnvironmentOverriddenBadge needs. */
  return render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{node}</TooltipProvider>
    </QueryClientProvider>,
  )
}

/** Opens a file row by the plain-text name the row shows, not the raw filename. */
export async function open(humanName: string) {
  fireEvent.click(await screen.findByText(humanName))
}

/** The tab as the service page draws it, with `?file=` kept in state. */
export function Settings({ service }: { service: string }) {
  const [file, setFile] = useState<string | undefined>()
  return <ServiceSettings service={service} file={file} onFile={setFile} />
}
