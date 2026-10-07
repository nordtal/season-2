import { describe, expect, it } from "vitest"

import { fileTitle, serviceTitle } from "@/lib/words"

describe("file names", () => {
  it("are Capital Case and never say a segment twice", () => {
    expect(fileTitle("voicechat/voicechat-server.properties")).toBe("Voicechat Server")
    expect(fileTitle("journeymap/journeymap-server.toml")).toBe("Journeymap Server")
    expect(fileTitle("bStats/config.yml")).toBe("bStats Config")
    expect(fileTitle("spark/config.json")).toBe("Spark Config")
  })
})

describe("service names", () => {
  it("are the name each service goes by", () => {
    expect(serviceTitle("smp")).toBe("SMP")
    expect(serviceTitle("discord-bot")).toBe("Discord Bot")
    expect(serviceTitle("hunger-games")).toBe("Hunger Games")
  })
})
