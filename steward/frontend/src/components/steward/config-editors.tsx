import type { ComponentType } from "react"

import type { ConfigDocument } from "@/lib/api"
import type { Target } from "@/components/steward/settings-view"
import { MilestonesEditor, readsTrack } from "@/components/steward/milestones/milestones-editor"

/**
 * The custom editors a plugin's descriptor can name for one of its groups, by the name it uses there.
 *
 * A group whose editor is not here, or whose schema the editor cannot read, is drawn with the form built from its
 * schema, so a jar newer or older than this page still shows every setting it has.
 */

/** What a custom editor is handed: the group as steward answered it, and a jump into it, if any. */
export type CustomEditorProps = { file: string; document: ConfigDocument; target: Target | null }

/** A custom editor and the test of whether it can draw a given group. */
type Editor = { draw: ComponentType<CustomEditorProps>; reads: (document: ConfigDocument) => boolean }

const EDITORS: Readonly<Record<string, Editor>> = {
  milestones: { draw: MilestonesEditor, reads: readsTrack },
}

/** The editor `name` stands for, or none for no name, for one this page does not know and for a group it cannot read. */
export function customEditor(
  name: string | undefined,
  document: ConfigDocument,
): ComponentType<CustomEditorProps> | undefined {
  const editor = name !== undefined && Object.hasOwn(EDITORS, name) ? EDITORS[name] : undefined
  return editor?.reads(document) ? editor.draw : undefined
}
