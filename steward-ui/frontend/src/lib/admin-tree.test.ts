import { describe, expect, it } from "vitest"

import { adminsBelow } from "@/lib/admin-tree"

const TREE = [
  { discordId: "root", admin: true, adminGrantedBy: null },
  { discordId: "a", admin: true, adminGrantedBy: "root" },
  { discordId: "b", admin: true, adminGrantedBy: "a" },
  { discordId: "c", admin: true, adminGrantedBy: "root" },
  { discordId: "gone", admin: false, adminGrantedBy: null },
]

describe("adminsBelow", () => {
  it("gives the root everybody but itself", () => {
    expect([...adminsBelow(TREE, "root")].sort()).toEqual(["a", "b", "c"])
  })

  it("gives an admin only their own branch, never a sibling or anybody above", () => {
    expect([...adminsBelow(TREE, "a")]).toEqual(["b"])
  })

  it("gives a leaf nobody", () => {
    expect(adminsBelow(TREE, "b").size).toBe(0)
  })

  it("gives nobody to somebody who is not signed in", () => {
    expect(adminsBelow(TREE, undefined).size).toBe(0)
  })
})
