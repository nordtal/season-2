import { describe, expect, it } from "vitest"

import { fileTitle, serviceTitle, translationsTitle } from "@/lib/words"

describe("file names", () => {
  it("are Capital Case and never say a segment twice", () => {
    expect(fileTitle("voicechat/voicechat-server.properties")).toBe("Voicechat Server")
    expect(fileTitle("journeymap/journeymap-server.toml")).toBe("Journeymap Server")
    expect(fileTitle("bStats/config.yml")).toBe("bStats Config")
    expect(fileTitle("spark/config.json")).toBe("Spark Config")
  })
})

describe("translations", () => {
  it("are named after the service in the name it goes by", () => {
    expect(translationsTitle({ service: "smp", module: "smp" })).toBe("SMP Translations")
    expect(translationsTitle({ service: "discord-bot", module: "" })).toBe("Discord Bot Translations")
    expect(serviceTitle("hunger-games")).toBe("Hunger Games")
  })
})
