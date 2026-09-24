import { describe, expect, it } from "vitest"

import { configTitle, fileTitle, serviceTitle, translationsTitle } from "@/lib/words"

describe("file names", () => {
  it("are Capital Case and never say a segment twice", () => {
    expect(fileTitle("voicechat/voicechat-server.properties")).toBe("Voicechat Server")
    expect(fileTitle("journeymap/journeymap-server.toml")).toBe("Journeymap Server")
    expect(fileTitle("bStats/config.yml")).toBe("bStats Config")
    expect(fileTitle("spark/config.json")).toBe("Spark Config")
  })

  it("drop the folder of the service's own plugin and name any other plugin first", () => {
    expect(configTitle({ service: "smp", name: "smp/milestones.yml", plugin: "SMP" })).toBe("Milestones")
    expect(configTitle({ service: "discord-bot", name: "bot.yml", plugin: null })).toBe("Bot")
    expect(configTitle({ service: "smp", name: "DisplayTags/config.yml", plugin: "Display Tags" })).toBe(
      "Display Tags Config",
    )
    expect(configTitle({ service: "proxy", name: "voicechat/voicechat-proxy.properties", plugin: "voicechat" })).toBe(
      "Voicechat Proxy",
    )
  })
})

describe("translations", () => {
  it("are named after the service in the name it goes by", () => {
    expect(translationsTitle({ service: "smp", module: "smp" })).toBe("SMP Translations")
    expect(translationsTitle({ service: "discord-bot", module: "" })).toBe("Discord Bot Translations")
    expect(serviceTitle("hunger-games")).toBe("Hunger Games")
  })
})
