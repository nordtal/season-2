import type { ComponentType } from "react"

import type { ConfigDocument } from "@/lib/api"
import type { Target } from "@/components/steward/settings-view"
import { MilestonesEditor, readsTrack } from "@/components/steward/milestones/milestones-editor"

/**
 * The custom editors that a plugin's descriptor can name for one of its groups, by the name the descriptor uses.
 *
 * A group is drawn with the form built from its schema when its editor is missing here, or when the editor cannot read the schema. So a jar newer or older than this page still shows every setting it has.
 */

/** The props of a custom editor: the group as steward answered it, and a jump into it, if any. */
export type CustomEditorProps = { file: string; document: ConfigDocument; target: Target | null }

/** A custom editor and the test of whether it can draw a given group. */
type Editor = { draw: ComponentType<CustomEditorProps>; reads: (document: ConfigDocument) => boolean }

const EDITORS: Readonly<Record<string, Editor>> = {
  milestones: { draw: MilestonesEditor, reads: readsTrack },
}

/**
 * Returns the editor that `name` stands for. It returns none when there is no name, when this page does not know the name, or when the editor cannot read the group.
 */
export function customEditor(
  name: string | undefined,
  document: ConfigDocument,
): ComponentType<CustomEditorProps> | undefined {
  const editor = name !== undefined && Object.hasOwn(EDITORS, name) ? EDITORS[name] : undefined
  return editor?.reads(document) ? editor.draw : undefined
}
