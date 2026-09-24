import { describe, expect, it } from "vitest"
import { continuesPrevious, parseLogLine, stripDockerStamp } from "./log-line"

describe("parseLogLine", () => {
  it("takes a Paper line apart and keeps the plugin as the source", () => {
    expect(
      parseLogLine(
        "2026-09-23T21:43:12.728350469Z [06:00:40] [Server thread/INFO]: [voicechat] Disconnecting client hmtill",
      ),
    ).toEqual({
      kind: "parsed",
      time: "06:00:40",
      level: "INFO",
      source: "voicechat",
      text: "Disconnecting client hmtill",
    })
  })

  it("names a Paper logger by its last component and reads WARN", () => {
    expect(
      parseLogLine(
        "[10:09:02] [Server thread/WARN]: [net.minecraft.world.level.block.entity.BlockEntity] Serialization errors:",
      ),
    ).toMatchObject({ level: "WARN", source: "BlockEntity", text: "Serialization errors:" })
  })

  it("leaves the source empty when a Paper line has no prefix", () => {
    expect(parseLogLine("[06:25:31] [Server thread/WARN]: Can't keep up!")).toMatchObject({
      kind: "parsed",
      source: "",
      text: "Can't keep up!",
    })
  })

  it("takes the Velocity logger out of its brackets", () => {
    expect(
      parseLogLine(
        "[06:25:38] [Netty epoll Worker #2/INFO] [com.velocitypowered.proxy.connection.client.ConnectedPlayer]: [connected player] hmtill has connected",
      ),
    ).toEqual({
      kind: "parsed",
      time: "06:25:38",
      level: "INFO",
      source: "ConnectedPlayer",
      text: "[connected player] hmtill has connected",
    })
  })

  it("reads Logback, with and without a thread, and drops the milliseconds", () => {
    expect(
      parseLogLine(
        "2026-09-23T02:46:48.902562213Z 04:46:21.317 [main] INFO  eu.nordtal.s2.discordbot.AccessBot - access-bot is up",
      ),
    ).toEqual({
      kind: "parsed",
      time: "04:46:21",
      level: "INFO",
      source: "AccessBot",
      text: "access-bot is up",
    })
    expect(
      parseLogLine("01:29:28.846 ERROR e.n.s.s.worker.serve.UpdateServer - Listening failed"),
    ).toMatchObject({ level: "ERROR", source: "UpdateServer", text: "Listening failed" })
  })

  it("leaves a stack trace raw and lets it carry on the error above", () => {
    const at = parseLogLine("2026-09-23T21:43:12.7Z \tat eu.nordtal.s2.smp.Smp.onEnable(Smp.java:42)")
    expect(at).toEqual({ kind: "raw", text: "\tat eu.nordtal.s2.smp.Smp.onEnable(Smp.java:42)" })
    expect(continuesPrevious(at.text)).toBe(true)
    expect(continuesPrevious("Caused by: java.io.IOException: gone")).toBe(true)
    expect(parseLogLine("There are 0 of a max of 40 players online:")).toEqual({
      kind: "raw",
      text: "There are 0 of a max of 40 players online:",
    })
    expect(continuesPrevious("There are 0 of a max of 40 players online:")).toBe(false)
  })

  it("strips only Docker's own timestamp", () => {
    expect(stripDockerStamp("2026-09-23T21:43:12.726838683Z [23:43:12] x")).toBe("[23:43:12] x")
    expect(stripDockerStamp("[23:43:12] x")).toBe("[23:43:12] x")
  })
})
