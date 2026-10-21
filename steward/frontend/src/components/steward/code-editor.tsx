import { useEffect, useRef } from "react"
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands"
import { json } from "@codemirror/lang-json"
import { yaml } from "@codemirror/lang-yaml"
import { HighlightStyle, syntaxHighlighting, syntaxTree } from "@codemirror/language"
import { linter, lintGutter, type Diagnostic } from "@codemirror/lint"
import { EditorState, type Extension } from "@codemirror/state"
import { EditorView, keymap } from "@codemirror/view"
import { tags } from "@lezer/highlight"

import { t } from "@/lib/texts"

/** The formats the editor reads, by a file's extension; any other file is plain text. */
function language(path: string): Extension[] {
  if (/\.ya?ml$/i.test(path)) return [yaml(), syntaxErrors]
  if (/\.json$/i.test(path)) return [json(), syntaxErrors]
  return []
}

/** Marks where the parser could not read on, which is where the server's own parser stops too. */
const syntaxErrors = linter((view) => {
  const found: Diagnostic[] = []
  syntaxTree(view.state).iterate({
    enter: (node) => {
      if (!node.type.isError) return
      found.push({
        from: node.from,
        to: Math.max(node.to, Math.min(node.from + 1, view.state.doc.length)),
        severity: "error",
        message: t("steward.settings.syntax-error"),
      })
    },
  })
  return found
})

const highlight = HighlightStyle.define([
  { tag: [tags.propertyName, tags.definition(tags.propertyName)], color: "var(--mark)" },
  { tag: [tags.string, tags.special(tags.string)], color: "var(--success)" },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], color: "var(--warning)" },
  { tag: [tags.comment, tags.meta], color: "var(--muted-foreground)" },
])

const theme = EditorView.theme({
  "&": { fontSize: "inherit" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { fontFamily: "inherit", lineHeight: "1.5rem" },
  ".cm-content": { caretColor: "var(--foreground)", padding: "0.5rem 0" },
  ".cm-gutters": { backgroundColor: "transparent", border: "none" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--destructive)" },
  ".cm-tooltip": {
    backgroundColor: "var(--popover)",
    color: "var(--popover-foreground)",
    borderColor: "var(--border)",
  },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": { backgroundColor: "var(--accent)" },
})

/**
 * A file's text with its format's colours and its syntax errors marked. The text stays the parent's: a change outside, such as a discard, replaces the document.
 */
export function CodeEditor({
  path,
  value,
  onChange,
  readOnly,
  label,
}: {
  path: string
  value: string
  onChange: (text: string) => void
  readOnly: boolean
  label: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView>(null)
  // The editor is made once per file and reads these at its own pace, so neither recreates it.
  const latest = useRef({ value, onChange })

  useEffect(() => {
    latest.current.onChange = onChange
  }, [onChange])

  useEffect(() => {
    const editor = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: latest.current.value,
        extensions: [
          history(),
          keymap.of([...defaultKeymap, ...historyKeymap]),
          language(path),
          lintGutter(),
          syntaxHighlighting(highlight),
          theme,
          EditorState.readOnly.of(readOnly),
          EditorView.contentAttributes.of({ "aria-label": label, spellcheck: "false" }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) latest.current.onChange(update.state.doc.toString())
          }),
        ],
      }),
    })
    view.current = editor
    return () => editor.destroy()
  }, [path, readOnly, label])

  useEffect(() => {
    latest.current.value = value
    const editor = view.current
    if (!editor || editor.state.doc.toString() === value) return
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
  }, [value])

  return (
    <div
      ref={host}
      className="min-h-64 w-full overflow-hidden rounded-lg border border-input font-mono text-xs focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50 max-md:text-base dark:bg-input/30"
    />
  )
}
