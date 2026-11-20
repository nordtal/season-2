/** The service whose page has the NPCs tab. */
export const NPC_PAGE = "smp"

/** Where the list of NPCs is: the NPCs tab of the SMP page. */
export const NPCS_PATH = { to: "/services/$name", params: { name: NPC_PAGE }, search: { tab: "npcs" } } as const

/** Where one NPC's editor is: the NPCs tab with that NPC open. */
export function npcPath(id: string) {
  return { to: "/services/$name", params: { name: NPC_PAGE }, search: { tab: "npcs", npc: id } } as const
}
