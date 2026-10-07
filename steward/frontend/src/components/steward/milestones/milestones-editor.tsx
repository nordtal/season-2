import { useMemo } from "react"

import type { ConfigDocument } from "@/lib/api"
import { Failure } from "@/components/steward/query-state"
import { noticeOf, SaveDraft, useGroupDraft } from "@/components/steward/group-draft"
import { type SectionValues, sectionsFromEntry } from "@/components/steward/repeatable-cards"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { PinnedRow } from "@/components/steward/settings-view"
import { TrackRows } from "@/components/steward/milestones/rows"
import { type TrackSchema, trackEdits, trackSchema } from "@/components/steward/milestones/model"

/** A draft value that is a list of sections, which is what the track's key holds. */
function sectionsIn(value: string | string[] | SectionValues[] | undefined): SectionValues[] | undefined {
  return Array.isArray(value) && value.every((item) => typeof item === "object") ? value : undefined
}

/** Whether this editor can draw `document`: a track whose milestones and objectives the schema describes. */
export function readsTrack(document: ConfigDocument): boolean {
  return trackSchema(document.entries) !== null
}

/** The milestones group drawn compactly, saved like every other group. */
export function MilestonesEditor({ file, document }: CustomEditorProps) {
  const schema = trackSchema(document.entries)
  const { draft, count, set, submit, discard, save } = useGroupDraft(file, document)
  if (schema === null) return null
  const drafted = draft[schema.entry.path]
  const notice = noticeOf(document)
  return (
    <div className="flex flex-col gap-3">
      {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      {save.error ? <Failure error={save.error} /> : null}
      <Track
        id={schema.entry.path}
        schema={schema}
        stored={schema.entry}
        drafted={sectionsIn(drafted)}
        disabled={!document.writable}
        onChange={(track) => set(schema.entry.path, track)}
      />
      <PinnedRow>
        <SaveDraft
          count={count}
          writable={document.writable}
          pending={save.isPending}
          onSave={submit}
          onDiscard={discard}
        />
      </PinnedRow>
    </div>
  )
}

/** The track, from the draft where there is one and from the stored group where not. */
function Track({
  id,
  schema,
  stored,
  drafted,
  disabled,
  onChange,
}: {
  id: string
  schema: TrackSchema
  stored: TrackSchema["entry"]
  drafted: SectionValues[] | undefined
  disabled: boolean
  onChange: (track: SectionValues[]) => void
}) {
  const fromStore = useMemo(() => sectionsFromEntry(stored), [stored])
  const track = drafted ?? fromStore
  return (
    <TrackRows id={id} schema={schema} track={track} edits={trackEdits(track, schema, onChange)} disabled={disabled} />
  )
}
