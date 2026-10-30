import { describe, expect, it } from "vitest"

import { standbyOf, withRunningStandbys } from "@/lib/services"

function row(service: string, state: string, standby?: boolean) {
  return { service, state, standby }
}

describe("a standby in the list of services", () => {
  const names = ["caddy", "proxy", "limbo", "smp"]

  it("stands directly below the service it stands in for while it runs", () => {
    const rows = [row("proxy-standby", "running", true), row("limbo-standby", "running", true)]
    expect(withRunningStandbys(names, rows)).toEqual([
      "caddy",
      "proxy",
      "proxy-standby",
      "limbo",
      "limbo-standby",
      "smp",
    ])
  })

  it("is left out while it rests", () => {
    expect(withRunningStandbys(names, [row("proxy-standby", "exited", true)])).toEqual(names)
  })

  it("is only a service that says it is one", () => {
    expect(withRunningStandbys(names, [row("proxy", "running")])).toEqual(names)
    expect(standbyOf("proxy-standby")).toBe("proxy")
    expect(standbyOf("-standby")).toBeUndefined()
    expect(standbyOf("proxy")).toBeUndefined()
  })
})
