import { useRef, useState } from "react"
import type { UIEvent } from "react"

import type { EditableRawConfigDocument, RawConfigFormat } from "@/lib/api"
import { useSaveRawConfig } from "@/lib/queries"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Failure } from "@/components/steward/query-state"

/**
 * The editor for a file that did not split into keys, or a config with a mistake in it.
 *
 * The worker checks syntax and answers a problem as a warning on the save itself; nothing is refused.
 */
export function RawConfigEditor({
  file,
  document,
  origin,
}: {
  file: string
  document: EditableRawConfigDocument
  /** Who wrote the file; without it the editor says nothing above the text. */
  origin?: "nordtal" | "third-party"
}) {
  const [content, setContent] = useState(document.content)
  const [warnings, setWarnings] = useState<string[]>([])
  const save = useSaveRawConfig(file)

  /** The answer to a save is the file byte for byte, so a successful write settles the draft on it. */
  const [lastDocument, setLastDocument] = useState(document)
  if (lastDocument !== document) {
    setLastDocument(document)
    setContent(document.content)
    setWarnings([])
  }

  const dirty = content !== document.content
  const format = formatOf(document.name)

  function submit() {
    save.mutate({ revision: document.revision, content }, { onSuccess: (saved) => setWarnings(saved.warnings) })
  }

  return (
    <div className="flex flex-col gap-4">
      {origin === "nordtal" ? (
        /** Raw text in a Nordtal file means it did not parse; a third-party file is text and nothing more. */
        <details className="text-sm text-muted-foreground">
          <summary className="cursor-pointer">Shown as text, it did not parse.</summary>
          {document.reason ? <p className="mt-1 font-mono text-xs">{document.reason}</p> : null}
        </details>
      ) : null}

      {save.error ? <Failure error={save.error} /> : null}

      {warnings.length > 0 ? (
        <Alert>
          <AlertTitle>Saved, with something worth checking.</AlertTitle>
          <AlertDescription>
            <ul className="list-disc pl-4">
              {warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      ) : null}

      <HighlightedTextarea
        format={format}
        value={content}
        onChange={setContent}
        readOnly={!document.writable}
        ariaLabel={`Raw content of ${document.name}`}
      />

      {document.writable ? (
        <div className="sticky bottom-0 flex flex-wrap items-center justify-between gap-3 border-t border-border bg-background/95 py-3 backdrop-blur">
          <p className="text-sm text-muted-foreground">{dirty ? "Unsaved changes." : null}</p>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={!dirty || save.isPending}
              onClick={() => setContent(document.content)}
            >
              Discard
            </Button>
            <Button type="button" size="sm" disabled={!dirty || save.isPending} onClick={submit}>
              {save.isPending ? "Saving…" : "Save"}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}

/** The format from the file name's last extension, kept in step with `RawSyntax.formatOf` by hand. */
export function formatOf(fileName: string): RawConfigFormat {
  const lower = fileName.toLowerCase()
  const slash = lower.lastIndexOf("/")
  const leaf = slash >= 0 ? lower.slice(slash + 1) : lower
  if (leaf.endsWith(".yml") || leaf.endsWith(".yaml")) return "yaml"
  if (leaf.endsWith(".json")) return "json"
  if (leaf.endsWith(".toml")) return "toml"
  if (leaf.endsWith(".properties")) return "properties"
  return "text"
}

/**
 * A `<textarea>` with transparent text over a `<pre>` that draws the colouring and sizes the box.
 *
 * Both layers share one class list, since they must agree on font, padding and wrapping to the pixel.
 */
function HighlightedTextarea({
  format,
  value,
  onChange,
  readOnly,
  ariaLabel,
}: {
  format: RawConfigFormat
  value: string
  onChange: (value: string) => void
  readOnly: boolean
  ariaLabel: string
}) {
  const preRef = useRef<HTMLPreElement>(null)

  /** Keeps the `<pre>` scrolled with the textarea, so the colouring stays under the text. */
  function syncScroll(event: UIEvent<HTMLTextAreaElement>) {
    if (!preRef.current) return
    preRef.current.scrollTop = event.currentTarget.scrollTop
    preRef.current.scrollLeft = event.currentTarget.scrollLeft
  }

  const shared =
    "col-start-1 row-start-1 m-0 min-h-40 max-h-[32rem] overflow-auto whitespace-pre-wrap break-words rounded-md border border-input px-3 py-2 font-mono text-sm"

  return (
    <div className="grid">
      <pre ref={preRef} aria-hidden className={`${shared} pointer-events-none select-none bg-input/30`}>
        {tokenize(format, value).map((token, index) => (
          <span key={index} className={token.className}>
            {token.text}
          </span>
        ))}
      </pre>
      <textarea
        aria-label={ariaLabel}
        value={value}
        readOnly={readOnly}
        spellCheck={false}
        onScroll={syncScroll}
        onChange={(event) => onChange(event.target.value)}
        className={`${shared} resize-none border-transparent bg-transparent text-transparent caret-foreground outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50`}
      />
    </div>
  )
}

type Token = { text: string; className?: string }

const CLASS = {
  comment: "text-muted-foreground italic",
  key: "text-foreground font-medium",
  section: "text-foreground font-semibold",
  punctuation: "text-muted-foreground",
  string: "text-foreground/90",
  literal: "text-[#8f8b82]",
}

/** Every token of a file, joined with real `"\n"` characters so the `<pre>` wraps like the plain string. */
function tokenize(format: RawConfigFormat, content: string): Token[] {
  if (format === "text") {
    return [{ text: content }]
  }
  const lines = content.split("\n")
  const tokens: Token[] = []
  lines.forEach((line, index) => {
    tokens.push(...tokenizeLine(format, line))
    if (index < lines.length - 1) tokens.push({ text: "\n" })
  })
  return tokens
}

export function tokenizeLine(format: RawConfigFormat, line: string): Token[] {
  switch (format) {
    case "yaml":
      return tokenizeYamlLine(line)
    case "json":
      return tokenizeJsonLine(line)
    case "toml":
      return tokenizeTomlLine(line)
    case "properties":
      return tokenizePropertiesLine(line)
    case "text":
      return [{ text: line }]
    default: {
      const exhaustive: never = format
      throw new Error(`unreachable config format: ${JSON.stringify(exhaustive)}`)
    }
  }
}

function tokenizeYamlLine(line: string): Token[] {
  const tokens: Token[] = []
  let rest = line

  const indent = /^\s*/.exec(rest)![0]
  if (indent) tokens.push({ text: indent })
  rest = rest.slice(indent.length)

  if (rest.startsWith("#")) {
    tokens.push({ text: rest, className: CLASS.comment })
    return tokens
  }

  // A block sequence's own marker: `- item`, or a bare `-` with nothing after it yet.
  const dash = /^-(\s+|$)/.exec(rest)
  if (dash) {
    tokens.push({ text: dash[0], className: CLASS.punctuation })
    rest = rest.slice(dash[0].length)
    if (rest.startsWith("#")) {
      tokens.push({ text: rest, className: CLASS.comment })
      return tokens
    }
  }

  /** A plain key ends at the first colon followed by whitespace or the line end, so `12:00: x` works. */
  const key = /^([^:#]+?)(:)(\s|$)/.exec(rest)
  if (key) {
    tokens.push({ text: key[1], className: CLASS.key })
    tokens.push({ text: key[2], className: CLASS.punctuation })
    rest = rest.slice(key[1].length + 1)
  }

  if (rest.length > 0) tokens.push(...tokenizeScalarValue(rest))
  return tokens
}

function tokenizeTomlLine(line: string): Token[] {
  const indent = /^\s*/.exec(line)![0]
  const rest = line.slice(indent.length)
  const tokens: Token[] = indent ? [{ text: indent }] : []

  if (rest.startsWith("#")) {
    tokens.push({ text: rest, className: CLASS.comment })
    return tokens
  }

  const header = /^\[{1,2}[^\]]*\]{1,2}/.exec(rest)
  if (header) {
    tokens.push({ text: header[0], className: CLASS.section })
    const trailing = rest.slice(header[0].length)
    if (trailing) tokens.push(...tokenizeScalarValue(trailing))
    return tokens
  }

  const key = /^([^=#]+?)(\s*=\s*)(.*)$/.exec(rest)
  if (!key) {
    tokens.push({ text: rest })
    return tokens
  }
  tokens.push({ text: key[1], className: CLASS.key })
  tokens.push({ text: key[2], className: CLASS.punctuation })
  if (key[3]) tokens.push(...tokenizeScalarValue(key[3]))
  return tokens
}

/** A YAML or TOML value: a quoted string, a number, a literal or plain text, plus a trailing comment. */
function tokenizeScalarValue(text: string): Token[] {
  const tokens: Token[] = []
  const leading = /^\s*/.exec(text)![0]
  if (leading) tokens.push({ text: leading })
  let value = text.slice(leading.length)

  const hashAt = findUnquotedHash(value)
  let comment = ""
  if (hashAt >= 0) {
    comment = value.slice(hashAt)
    value = value.slice(0, hashAt)
  }

  if (value.length > 0) tokens.push(scalarToken(value))
  if (comment) tokens.push({ text: comment, className: CLASS.comment })
  return tokens
}

function scalarToken(value: string): Token {
  /** Classified by the trimmed value, while the token keeps the untrimmed text so nothing goes missing. */
  const trimmed = value.replace(/\s+$/, "")
  return { text: value, className: classifyScalar(trimmed) }
}

function classifyScalar(value: string): string | undefined {
  if (/^(".*"|'.*')$/.test(value)) return CLASS.string
  if (/^(true|false|null|~)$/i.test(value)) return CLASS.literal
  if (/^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(value)) return CLASS.literal
  return undefined
}

/** The index of the first unquoted `#` that starts a YAML comment, or -1; a `#` glued to a word is not one. */
function findUnquotedHash(text: string): number {
  let inSingle = false
  let inDouble = false
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (char === "'" && !inDouble) inSingle = !inSingle
    else if (char === '"' && !inSingle) inDouble = !inDouble
    else if (char === "#" && !inSingle && !inDouble && (i === 0 || /\s/.test(text[i - 1]))) {
      return i
    }
  }
  return -1
}

function tokenizePropertiesLine(line: string): Token[] {
  const comment = /^(\s*)([#!].*)$/.exec(line)
  if (comment) {
    const tokens: Token[] = []
    if (comment[1]) tokens.push({ text: comment[1] })
    tokens.push({ text: comment[2], className: CLASS.comment })
    return tokens
  }

  const kv = /^(\s*)([^=:\s][^=:]*?)(\s*[=:]\s*)(.*)$/.exec(line)
  if (!kv) return [{ text: line }]
  const [, indent, key, separator, value] = kv
  const tokens: Token[] = []
  if (indent) tokens.push({ text: indent })
  tokens.push({ text: key, className: CLASS.key })
  tokens.push({ text: separator, className: CLASS.punctuation })
  if (value) tokens.push({ text: value, className: CLASS.string })
  return tokens
}

const JSON_TOKEN = /"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?|true|false|null|[{}[\],:]|\s+/g

function tokenizeJsonLine(line: string): Token[] {
  const tokens: Token[] = []
  let lastIndex = 0
  JSON_TOKEN.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = JSON_TOKEN.exec(line))) {
    if (match.index > lastIndex) tokens.push({ text: line.slice(lastIndex, match.index) })
    const text = match[0]
    lastIndex = match.index + text.length
    if (/^\s+$/.test(text)) {
      tokens.push({ text })
    } else if (text.startsWith('"')) {
      const isKey = /^\s*:/.test(line.slice(lastIndex))
      tokens.push({ text, className: isKey ? CLASS.key : CLASS.string })
    } else if (/^[{}[\],:]$/.test(text)) {
      tokens.push({ text, className: CLASS.punctuation })
    } else {
      tokens.push({ text, className: CLASS.literal })
    }
  }
  if (lastIndex < line.length) tokens.push({ text: line.slice(lastIndex) })
  return tokens
}
