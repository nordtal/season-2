/**
 * The admin tree: every admin names who granted them admin rights, and the root names nobody.
 *
 * The server decides every revocation. This code only hides the button where the server would refuse.
 */
export type AdminNode = {
  discordId: string
  admin: boolean
  adminGrantedBy?: string | null
}

/**
 * Returns everybody strictly below `me`, the admins that `me` may revoke. The set is empty when `me` is no admin.
 */
export function adminsBelow(people: readonly AdminNode[], me: string | undefined): Set<string> {
  const below = new Set<string>()
  if (!me) return below
  const children = new Map<string, string[]>()
  for (const person of people) {
    if (!person.admin || !person.adminGrantedBy) continue
    const list = children.get(person.adminGrantedBy) ?? []
    list.push(person.discordId)
    children.set(person.adminGrantedBy, list)
  }
  const queue = [...(children.get(me) ?? [])]
  while (queue.length > 0) {
    const next = queue.shift()!
    if (below.has(next)) continue
    below.add(next)
    queue.push(...(children.get(next) ?? []))
  }
  return below
}
