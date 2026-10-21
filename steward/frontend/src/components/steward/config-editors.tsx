import type { ComponentType } from "react"

import type { ConfigDocument } from "@/lib/api"
import type { Target } from "@/components/steward/settings-view"
import { AwardsEditor, readsAwards } from "@/components/steward/game-groups/awards-editor"
import { DuelsEditor, readsDuels } from "@/components/steward/game-groups/duels-editor"
import { WheelEditor, readsWheel } from "@/components/steward/game-groups/wheel-editor"
import { MilestonesEditor, readsTrack } from "@/components/steward/milestones/milestones-editor"

/**
 * A group is drawn with the form built from its schema when its editor is missing here or cannot read the schema, so a jar newer or older than this page still shows every setting.
 */

export type CustomEditorProps = { file: string; document: ConfigDocument; target: Target | null }

type Editor = { draw: ComponentType<CustomEditorProps>; reads: (document: ConfigDocument) => boolean }

const EDITORS: Readonly<Record<string, Editor>> = {
  milestones: { draw: MilestonesEditor, reads: readsTrack },
  "advancement-awards": { draw: AwardsEditor, reads: readsAwards },
  wheel: { draw: WheelEditor, reads: readsWheel },
  duels: { draw: DuelsEditor, reads: readsDuels },
}

/**
 * Returns none when there is no name, the name is unknown, or the editor cannot read the group.
 */
export function customEditor(
  name: string | undefined,
  document: ConfigDocument,
): ComponentType<CustomEditorProps> | undefined {
  const editor = name !== undefined && Object.hasOwn(EDITORS, name) ? EDITORS[name] : undefined
  return editor?.reads(document) ? editor.draw : undefined
}
