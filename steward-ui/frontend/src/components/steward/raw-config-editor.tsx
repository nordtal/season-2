import { useEffect, useRef, useState } from "react"
import type { UIEvent } from "react"
import { FileCode, Lock } from "lucide-react"

import type { EditableRawConfigDocument, RawConfigFormat } from "@/lib/api"
import { useSaveRawConfig } from "@/lib/queries"
import { humanFileName } from "@/components/steward/config-controls"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Failure } from "@/components/steward/query-state"

/**
 * The editor `RawConfigView` (`configuration.tsx`) hands a file to once steward/56's read-only
 * fallback has one to show - a file this process could not split into keys at all, or an ordinary
 * config with a mistake in it.
 *
 * **Format is decided by the file's own name, never by what parsed and what did not.** The same
 * four extensions {@link formatOf} mirrors the worker's own `RawSyntax.formatOf` with - YAML,
 * JSON, TOML, properties - and anything else is plain text. Neither side imports the other; the
 * two are kept true to each other only by both reading the same four endings, noted in each one's
 * own doc comment.
 *
 * **Syntax is checked on the worker, not in this browser.** `RawSyntax` already sits beside the
 * parsers this project actually uses to read these files at runtime - SnakeYAML and Gson are both
 * already on the worker's classpath, and a browser-side check would be a second, independent
 * opinion about what "valid YAML" means that could disagree with the one the server-side already
 * has. A save is one round trip either way, and it is the one round trip this editor makes.
 *
 * **Nothing here is ever refused.** A syntax problem comes back as a warning naming a line, on the
 * very same response that already carries the save - never a separate confirmation step, and never
 * a reason `useSaveRawConfig`'s promise rejects. See `ConfigApi#saveRaw` and `RawSyntax` for the
 * other half of this.
 */
export function RawConfigEditor({
  file,
  document,
}: {
  file: string
  document: EditableRawConfigDocument
}) {
  const [content, setContent] = useState(document.content)
  const [warnings, setWarnings] = useState<string[]>([])
  const save = useSaveRawConfig(file)

  // The answer to a save IS the file as it now reads (byte for byte here, since nothing on this
  // path re-serialises), so a successful write settles the draft on exactly what was written -
  // the same rule ConfigForm and BundleForm both follow.
  useEffect(() => {
    setContent(document.content)
    setWarnings([])
  }, [document])

  const dirty = content !== document.content
  const format = formatOf(document.name)

  function submit() {
    save.mutate(
      { revision: document.revision, content },
      { onSuccess: (saved) => setWarnings(saved.warnings) },
    )
  }

  return (
    <div className="flex flex-col gap-4">
      {document.writable ? (
        <Alert>
          <FileCode aria-hidden />
          <AlertTitle>Editable as raw text.</AlertTitle>
          <AlertDescription>
            Steward could not read {humanFileName(document.name)} ({document.name}) as a config
            file{document.reason ? `: ${document.reason}` : "."} It is shown and edited exactly as
            it stands on disk - {formatNameOf(format)} is checked on save, but a problem there is a
            warning naming the line, never a reason this refuses to save.
          </AlertDescription>
        </Alert>
      ) : (
        <Alert>
          <Lock aria-hidden />
          <AlertTitle>This file is mounted read-only.</AlertTitle>
          <AlertDescription>
            Steward could not read {humanFileName(document.name)} ({document.name}) as a config
            file{document.reason ? `: ${document.reason}` : "."} It is shown exactly as it stands
            on disk; the mount underneath it does not allow a save from here, so it cannot be
            edited either.
          </AlertDescription>
        </Alert>
      )}

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
          <p className="text-sm text-muted-foreground">
            {dirty ? "Unsaved changes." : "Nothing changed."}
          </p>
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

// -------------------------------------------------------------------------------------------
// Format detection - mirrors RawSyntax.formatOf on the worker (steward/60)
// -------------------------------------------------------------------------------------------

/**
 * The format this editor draws for a file, decided by the last extension on its own name - never
 * by sniffing its content, and never by what did or did not parse. Kept in step with the worker's
 * `RawSyntax.formatOf` by hand: a frontend module cannot import a worker enum, so the two agree
 * only because both read the same four endings.
 */
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

function formatNameOf(format: RawConfigFormat): string {
  switch (format) {
    case "yaml":
      return "YAML"
    case "json":
      return "JSON"
    case "toml":
      return "TOML"
    case "properties":
      return "properties syntax"
    case "text":
      return "nothing"
  }
}

// -------------------------------------------------------------------------------------------
// The editor itself: a plain textarea with a colour-matched layer of tokens behind it
// -------------------------------------------------------------------------------------------

/**
 * A `<textarea>` with syntax colouring behind it, not inside it - a textarea cannot render markup,
 * so the trick every editor this small uses is two elements occupying the same box: a `<pre>` in
 * normal flow, which is what gives the box its height, and the real `<textarea>` laid over it with
 * its own text made transparent. What the operator sees as coloured text is the `<pre>`; what they
 * type into is the `<textarea>` on top of it, whose caret is the only part of it still visible.
 *
 * **This is deliberately not one npm package.** `package.json` carries no code editor and no
 * highlighter of any kind (checked before writing this), and the four formats here are line-
 * oriented enough that a tokeniser small enough to read in one sitting covers them honestly. A
 * dependency like CodeMirror buys far more than this page needs, on a UI that ships inside a jar.
 *
 * **The two layers have to agree on font, padding, border and line wrapping down to the pixel** -
 * that is the entire risk of this technique, and the reason both share one class list below rather
 * than two similar ones that could drift apart.
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

  // Both layers scroll their own box; a container that clipped instead would mean the operator
  // could type past what is visible with nothing to bring it back into view.
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

// -------------------------------------------------------------------------------------------
// The tokeniser - one small function per format, line-oriented on purpose
// -------------------------------------------------------------------------------------------

type Token = { text: string; className?: string }

const CLASS = {
  comment: "text-muted-foreground italic",
  key: "text-foreground font-medium",
  section: "text-foreground font-semibold",
  punctuation: "text-muted-foreground",
  string: "text-foreground/90",
  literal: "text-[#8f8b82]",
}

/**
 * Every token of a whole file's text, in reading order, one line at a time - joined back together
 * with real {@code "\n"} characters rather than one element per line, so the wrapping this
 * produces is exactly the wrapping a single `<pre>` would give the same string on its own.
 */
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

  // A block sequence's own marker - `- item`, or a bare `-` with nothing after it yet.
  const dash = /^-(\s+|$)/.exec(rest)
  if (dash) {
    tokens.push({ text: dash[0], className: CLASS.punctuation })
    rest = rest.slice(dash[0].length)
    if (rest.startsWith("#")) {
      tokens.push({ text: rest, className: CLASS.comment })
      return tokens
    }
  }

  // A plain key ends at the first colon followed by whitespace or the end of the line - matching
  // ConfigFiles' own rule that a key may itself contain a colon (`12:00: something`).
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

/**
 * A YAML or TOML value: a quoted string, a number, a boolean/null literal, or plain text - plus
 * whatever trailing {@code # comment} follows it outside of quotes. Shared between the two formats
 * because both give a value the same three shapes.
 */
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

  if (value.length > 0) tokens.push(scalarToken(value));
  if (comment) tokens.push({ text: comment, className: CLASS.comment })
  return tokens
}

function scalarToken(value: string): Token {
  // Classified by the value with its own trailing whitespace stripped (`true `, with a trailing
  // space, is still the literal `true`) - but the token itself always carries the untrimmed text,
  // or the characters it stands for would quietly go missing from what the operator sees.
  const trimmed = value.replace(/\s+$/, "")
  return { text: value, className: classifyScalar(trimmed) }
}

function classifyScalar(value: string): string | undefined {
  if (/^(".*"|'.*')$/.test(value)) return CLASS.string
  if (/^(true|false|null|~)$/i.test(value)) return CLASS.literal
  if (/^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(value)) return CLASS.literal
  return undefined
}

/**
 * The first `#` outside of single or double quotes that also starts a comment by YAML's own rule -
 * at the beginning of the text, or with whitespace right before it - or -1 when there is none. A
 * `#` glued onto the previous character, the way a URL's own fragment is, is not a comment.
 */
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
