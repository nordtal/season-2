import type { ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { RawConfigEditor, formatOf, tokenizeLine } from "@/components/steward/raw-config-editor"
import type { EditableRawConfigDocument } from "@/lib/api"

/**
 * `RawConfigEditor` is what `RawConfigView` in `configuration.tsx` hands a raw file to since
 * steward/60 gave that view a save path. `configuration.test.tsx` already exercises this component
 * end to end, wired through the real file list and the real `ServiceSettings` tab; this file
 * is the narrower one - the tokeniser by itself, and the component alone against a fake
 * `PUT /api/config-raw/<file>` rather than the whole page.
 *
 * Every scenario below is a RED test first: `RawConfigEditor` and `formatOf`/`tokenizeLine` did not
 * exist before steward/60, so each assertion failed with "Cannot find module" until the file this
 * test names was written to make it pass.
 */

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  })
}

function document(over: Partial<EditableRawConfigDocument> = {}): EditableRawConfigDocument {
  return {
    service: "steward-worker",
    name: "config.yml",
    path: "steward-worker/config.yml",
    readable: true,
    writable: true,
    raw: true,
    reason: "line 1: the top of the file must be a set of keys, found a scalar instead",
    content: "one: 1\n",
    revision: "r1",
    ...over,
  }
}

function draw(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("formatOf", () => {
  it("reads the format off the file's own extension, case-insensitively", () => {
    expect(formatOf("config.yml")).toBe("yaml")
    expect(formatOf("config.YAML")).toBe("yaml")
    expect(formatOf("data.json")).toBe("json")
    expect(formatOf("Cargo.toml")).toBe("toml")
    expect(formatOf("server.properties")).toBe("properties")
    expect(formatOf("README.txt")).toBe("text")
    expect(formatOf("README")).toBe("text")
  })

  it("looks only at the leaf of a path, never a directory name", () => {
    expect(formatOf("plugins/smp.yml/README")).toBe("text")
    expect(formatOf("steward-worker/config.yml")).toBe("yaml")
  })
})

describe("tokenizeLine", () => {
  it("marks a YAML key and a trailing comment apart from the plain value between them", () => {
    const tokens = tokenizeLine("yaml", "host: postgres # not localhost")
    expect(tokens.map((token) => token.text).join("")).toBe("host: postgres # not localhost")
    expect(tokens.find((token) => token.text === "host")?.className).toContain("font-medium")
    expect(tokens.find((token) => token.text === "# not localhost")?.className).toContain("italic")
  })

  it("colours a YAML boolean and number as literals, and a quoted string as a string", () => {
    const boolLine = tokenizeLine("yaml", "enabled: true")
    const bool = boolLine.find((token) => token.text === "true")
    expect(bool?.className).toBeDefined()

    const numberLine = tokenizeLine("yaml", "port: 8080")
    expect(numberLine.find((token) => token.text === "8080")?.className).toBeDefined()

    const stringLine = tokenizeLine("yaml", 'name: "Nordtal"')
    expect(stringLine.find((token) => token.text === '"Nordtal"')?.className).toBeDefined()
  })

  it("does not split a URL's own # fragment into a comment", () => {
    const tokens = tokenizeLine("yaml", "url: https://example.com/page#section")
    const rebuilt = tokens.map((token) => token.text).join("")
    expect(rebuilt).toBe("url: https://example.com/page#section")
    expect(tokens.some((token) => token.className?.includes("italic"))).toBe(false)
  })

  it("tells a JSON key apart from a JSON string value", () => {
    const tokens = tokenizeLine("json", '"host": "postgres",')
    const key = tokens.find((token) => token.text === '"host"')
    const value = tokens.find((token) => token.text === '"postgres"')
    expect(key?.className).toContain("font-medium")
    expect(value?.className).not.toContain("font-medium")
  })

  it("reads a TOML table header and a key/value pair", () => {
    const header = tokenizeLine("toml", "[server]")
    expect(header[0].text).toBe("[server]")
    expect(header[0].className).toContain("font-semibold")

    const kv = tokenizeLine("toml", 'name = "nordtal"')
    expect(kv.find((token) => token.text === "name")?.className).toContain("font-medium")
  })

  it("reads a properties key/value pair and a comment line", () => {
    const kv = tokenizeLine("properties", "server.port=25565")
    expect(kv.find((token) => token.text === "server.port")?.className).toContain("font-medium")

    const comment = tokenizeLine("properties", "# a note")
    expect(comment.find((token) => token.text === "# a note")?.className).toContain("italic")
  })

  it("returns plain text untouched for the text fallback", () => {
    expect(tokenizeLine("text", "just words")).toEqual([{ text: "just words" }])
  })
})

describe("RawConfigEditor", () => {
  it("shows the file's content, and disables Save until it is edited", async () => {
    draw(<RawConfigEditor file="steward-worker/config.yml" document={document()} />)

    const editor = screen.getByLabelText("Raw content of config.yml") as HTMLTextAreaElement
    expect(editor.value).toBe("one: 1\n")
    expect(editor.readOnly).toBe(false)

    const save = screen.getByRole("button", { name: /Save/ }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    // The disabled Save already says there is nothing to save.
    expect(screen.queryByText("Nothing changed.")).toBeNull()
  })

  it("enables Save once the text changes, and Discard puts the original text back", () => {
    draw(<RawConfigEditor file="steward-worker/config.yml" document={document()} />)
    const editor = screen.getByLabelText("Raw content of config.yml") as HTMLTextAreaElement

    fireEvent.change(editor, { target: { value: "one: 1\ntwo: 2\n" } })
    expect(editor.value).toBe("one: 1\ntwo: 2\n")
    screen.getByText("Unsaved changes.")
    const save = screen.getByRole("button", { name: /Save/ }) as HTMLButtonElement
    expect(save.disabled).toBe(false)

    fireEvent.click(screen.getByRole("button", { name: /Discard/ }))
    expect(editor.value).toBe("one: 1\n")
    expect(save.disabled).toBe(true)
  })

  it("sends the revision and the typed text on Save, and shows a warning without losing the edit", async () => {
    const file = "steward-worker/config.yml"
    const broken = "one: 1\ntwo: [oops\n"
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === `/api/config-raw/${file}` && init?.method === "PUT") {
        const body = JSON.parse(String(init.body)) as { revision: string; content: string }
        expect(body).toEqual({ revision: "r1", content: broken })
        return json({
          ...document({ content: broken }),
          warnings: ["Line 2: not valid YAML: expected ',' or ']', but got :"],
        })
      }
      throw new Error(`the editor asked for ${url}, which this test did not expect`)
    })
    vi.stubGlobal("fetch", fetchMock)

    draw(<RawConfigEditor file={file} document={document()} />)
    const editor = screen.getByLabelText("Raw content of config.yml") as HTMLTextAreaElement
    fireEvent.change(editor, { target: { value: broken } })
    fireEvent.click(screen.getByRole("button", { name: /Save/ }))

    await screen.findByText("Line 2: not valid YAML: expected ',' or ']', but got :")
    expect(editor.value).toBe(broken)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("shows no Save button and a read-only textarea for a file mounted read-only", () => {
    draw(
      <RawConfigEditor
        file="smp/README.txt"
        document={document({ writable: false, name: "README.txt", content: "Read me.\n" })}
      />,
    )

    const editor = screen.getByLabelText("Raw content of README.txt") as HTMLTextAreaElement
    expect(editor.readOnly).toBe(true)
    expect(screen.queryByRole("button", { name: /Save/ })).toBeNull()
    expect(screen.queryByRole("button", { name: /Discard/ })).toBeNull()
  })

  it("says nothing above a third-party file - the text is all there is to it", () => {
    const { container } = draw(
      <RawConfigEditor
        file="smp/bStats/config.txt"
        origin="third-party"
        document={document({ name: "bStats/config.txt", reason: "not YAML" })}
      />,
    )
    expect(container.querySelector("[role=alert]")).toBeNull()
    expect(screen.queryByText(/Steward could not read/)).toBeNull()
  })

  it("gives a Nordtal file one line, with the parser's reason behind it", () => {
    const { container } = draw(
      <RawConfigEditor
        file="smp/smp/config.yml"
        origin="nordtal"
        document={document({ reason: "line 3: bad indent" })}
      />,
    )
    expect(container.querySelector("[role=alert]")).toBeNull()
    screen.getByText("Shown as text, it did not parse.")
    expect(container.querySelector("details")?.textContent).toContain("line 3: bad indent")
  })
})
