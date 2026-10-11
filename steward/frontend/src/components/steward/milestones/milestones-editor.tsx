import type { ConfigDocument } from "@/lib/api"
import { Failure } from "@/components/steward/query-state"
import { noticeOf, SaveDraft, useGroupDraft } from "@/components/steward/group-draft"
import { type SectionValues, sectionsFromEntry } from "@/components/steward/repeatable-cards"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { PinnedRow } from "@/components/steward/settings-view"
import { TrackSplit } from "@/components/steward/milestones/track-split"
import { trackEdits, trackSchema } from "@/components/steward/milestones/model"

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
  const track = sectionsIn(draft[schema.entry.path]) ?? sectionsFromEntry(schema.entry)
  const notice = noticeOf(document)
  return (
    <div className="flex flex-col gap-3">
      {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      {save.error ? <Failure error={save.error} /> : null}
      <TrackSplit
        id={schema.entry.path}
        schema={schema}
        track={track}
        edits={trackEdits(track, schema, (next) => set(schema.entry.path, next))}
        disabled={!document.writable}
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
