import type { ComponentType } from "react"

import type { ConfigDocument } from "@/lib/api"
import type { Target } from "@/components/steward/settings-view"

/**
 * The custom editors a plugin's descriptor can name for one of its groups, by the name it uses there.
 *
 * A group whose editor is not here is drawn with the form built from its schema, so a jar newer than this page
 * still shows every setting it has.
 */

/** What a custom editor is handed: the group as steward answered it, and a jump into it, if any. */
export type CustomEditorProps = { file: string; document: ConfigDocument; target: Target | null }

const EDITORS: Readonly<Record<string, ComponentType<CustomEditorProps>>> = {}

/** The editor `name` stands for, or none for no name and for one this page does not know. */
export function customEditor(name: string | undefined): ComponentType<CustomEditorProps> | undefined {
  return name !== undefined && Object.hasOwn(EDITORS, name) ? EDITORS[name] : undefined
}
