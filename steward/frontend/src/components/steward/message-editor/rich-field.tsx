import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { CodeIcon, EyeIcon } from "@phosphor-icons/react"
import { cn } from "@/lib/utils"

import type { MessageArg } from "@/lib/api"
import { useGlyphs } from "@/lib/queries"
import { normalize, parse, same, serialize } from "@/lib/rich-text"
import type { Format, Run } from "@/lib/rich-text"
import { t } from "@/lib/texts"
import { InputGroup, InputGroupAddon, InputGroupButton } from "@/components/ui/input-group"
import { ChoiceFields } from "@/components/steward/message-editor/cases"
import type { Fill } from "@/components/steward/message-editor/examples"
import type { Tones } from "@/components/steward/message-editor/preview"
import { SourceEditor } from "@/components/steward/message-editor/source-editor"
import { VisualEditor } from "@/components/steward/message-editor/visual-editor"

/**
 * One text in a field that shows it as it looks, with its tones, glyphs and values, or as it is written. A text the
 * parser cannot read opens as written. The plural and choice cases follow under the field.
 */
export function RichField({
  text,
  stored,
  onText,
  format,
  args,
  fill,
  tones,
  kinds,
  label,
  language,
  revision,
  invalid,
  disabled,
  start,
  end,
}: {
  text: string
  /** The saved text, kept as it is written while an edit returns to it. */
  stored: string
  onText: (text: string) => void
  format: Format
  args: MessageArg[]
  fill: Fill
  tones: Tones
  kinds: Record<string, string[]>
  label: string
  language: string
  /** Changes when the field shows another text, such as another language or variant, so the caret starts anew. */
  revision: string
  invalid?: boolean
  disabled?: boolean
  /** The controls at the start of the field's bottom row. */
  start?: ReactNode
  /** The controls at the end of the field's bottom row. */
  end?: ReactNode
}) {
  const [source, setSource] = useState(false)
  const glyphs = useGlyphs()
  const toneTags = useMemo(() => Object.keys(tones), [tones])
  const [runs, setRuns] = useRuns(text, format, toneTags, stored, onText)
  const readable = runs !== null
  const visual = readable && !source
  const common = { format, args, glyphs: glyphs.data ?? [], fill, tones, label, disabled }
  // The switch between as it looks and as written ends the tool row, so the bottom row exists only for `start` and `end`.
  const looks = (
    <span role="group" className="flex rounded-md bg-muted p-0.5">
      <InputGroupButton
        size="icon-xs"
        variant={visual ? "secondary" : "ghost"}
        aria-pressed={visual}
        aria-label={t("steward.message-editor.as-it-looks")}
        title={t("steward.message-editor.as-it-looks")}
        disabled={!readable}
        onClick={() => setSource(false)}
      >
        <EyeIcon aria-hidden />
      </InputGroupButton>
      <InputGroupButton
        size="icon-xs"
        variant={visual ? "ghost" : "secondary"}
        aria-pressed={!visual}
        aria-label={t("steward.message-editor.source")}
        title={t("steward.message-editor.source")}
        onClick={() => setSource(true)}
      >
        <CodeIcon aria-hidden />
      </InputGroupButton>
    </span>
  )
  return (
    <>
      <InputGroup className={cn("h-auto flex-col items-stretch", invalid && "border-destructive")}>
        {visual ? (
          <VisualEditor key={revision} {...common} runs={runs} onChange={setRuns} kinds={kinds} trailing={looks} />
        ) : (
          <SourceEditor {...common} text={text} onChange={onText} trailing={looks} />
        )}
        {start || end ? (
          <InputGroupAddon align="block-end" className="flex-wrap gap-1 border-t border-input pt-1.5 pb-1.5">
            {start}
            <span className="ml-auto flex items-center gap-1">{end}</span>
          </InputGroupAddon>
        ) : null}
      </InputGroup>
      {visual ? (
        <ChoiceFields key={revision} {...common} runs={runs} onChange={setRuns} kinds={kinds} language={language} />
      ) : null}
    </>
  )
}

function useRuns(
  text: string,
  format: Format,
  tones: readonly string[],
  stored: string,
  setText: (text: string) => void,
): [Run[] | null, (runs: Run[]) => void] {
  const [runs, setLocal] = useState<Run[] | null>(() => parse(text, format, tones))
  const written = useRef(text)
  const original = useMemo(() => parse(stored, format, tones), [stored, format, tones])

  useEffect(() => {
    if (text !== written.current) {
      written.current = text
      setLocal(parse(text, format, tones))
    }
  }, [text, format, tones])

  // The tones arrive after the first render, so the text is read again with them.
  useEffect(() => {
    setLocal(parse(written.current, format, tones))
  }, [format, tones])

  const update = (next: Run[]) => {
    setLocal(next)
    const value = original !== null && same(normalize(next), original) ? stored : serialize(next, format)
    written.current = value
    setText(value)
  }
  return [runs, update]
}
