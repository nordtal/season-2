import type { ConfigDocument } from "@/lib/api"
import { draftSections } from "@/components/steward/configuration"
import { GroupEditor } from "@/components/steward/group-draft"
import type { CustomEditorProps } from "@/components/steward/config-editors"
import { TrackSplit } from "@/components/steward/milestones/track-split"
import { trackEdits, trackSchema } from "@/components/steward/milestones/model"

export function readsTrack(document: ConfigDocument): boolean {
  return trackSchema(document.entries) !== null
}

export function MilestonesEditor({ file, document }: CustomEditorProps) {
  const schema = trackSchema(document.entries)
  if (schema === null) return null
  return (
    <GroupEditor file={file} document={document}>
      {(draft, set) => {
        const track = draftSections(draft, schema.entry)
        return (
          <TrackSplit
            id={schema.entry.path}
            schema={schema}
            track={track}
            edits={trackEdits(track, schema, (next) => set(schema.entry.path, next))}
            disabled={!document.writable}
          />
        )
      }}
    </GroupEditor>
  )
}
