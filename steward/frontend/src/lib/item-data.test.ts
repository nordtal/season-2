import { describe, expect, it } from "vitest"

import { byte, compound, double, float, int, itemData, list, stack, text } from "@/lib/item-data-fixtures"
import { type StoredItem, detailsOf, plainText, readItemData } from "@/lib/item-data"

const NAMES: Record<string, string> = {
  "minecraft:sharpness": "Sharpness",
  "minecraft:mending": "Mending",
  "minecraft:unbreaking": "Unbreaking",
}
const names = (_registry: string, id: string) => NAMES[id]
const TRANSLATIONS = new Map([["item.minecraft.elytra", "Elytra"]])

async function read(...args: Parameters<typeof stack>): Promise<StoredItem> {
  return readItemData(await itemData(stack(...args)))
}

describe("readItemData", () => {
  it("reads the material and the components of a stack Paper serialised", async () => {
    const item = await read("minecraft:diamond_sword", { "minecraft:repair_cost": int(3) })
    expect(item).toEqual({ id: "minecraft:diamond_sword", components: { "minecraft:repair_cost": 3 } })
  })

  it("reads a name in modified UTF-8, a character beyond the plane of the alphabet included", async () => {
    const item = await read("minecraft:diamond_sword", { "minecraft:custom_name": text("Ŝnow\u0000 ❄ 🗡") })
    expect(item.components["minecraft:custom_name"]).toBe("Ŝnow\u0000 ❄ 🗡")
  })

  it("refuses data that is no gzipped item", async () => {
    await expect(readItemData("AAEC")).rejects.toThrow(/not gzipped/)
    await expect(readItemData(await itemData(compound({ count: int(1) })))).rejects.toThrow(/no item/)
  })
})

describe("plainText", () => {
  it("flattens a component with its extra, a list, a wrapped element and a translation with its arguments", () => {
    expect(plainText(undefined, TRANSLATIONS)).toBe("")
    expect(
      plainText(
        [
          { "": "Kills: " },
          { text: "", extra: [{ text: "12", color: "gold" }] },
          { translate: "item.minecraft.elytra" },
          { translate: "x.unknown", fallback: "%2$s of %1$s, %s", with: ["Ann", "Bob"] },
          { translate: "x.nothing" },
        ],
        TRANSLATIONS,
      ),
    ).toBe("Kills: 12ElytraBob of Ann, Annx.nothing")
  })
})

describe("detailsOf", () => {
  it("names an enchanted item with lore, and lists its lore and every further detail one per line", async () => {
    const item = await read("minecraft:diamond_sword", {
      "minecraft:custom_name": compound({ text: text("Frostbite"), color: text("aqua"), italic: byte(0) }),
      "minecraft:lore": list([
        compound({ "": text("Forged in the north") }),
        compound({ text: text("Kills: "), extra: list([compound({ text: text("12"), color: text("gold") })]) }),
      ]),
      "minecraft:enchantments": compound({
        "minecraft:sharpness": int(5),
        "minecraft:mending": int(1),
        "minecraft:unbreaking": int(3),
      }),
      "minecraft:attribute_modifiers": list([
        compound({
          type: text("minecraft:attack_damage"),
          id: text("minecraft:base_attack_damage"),
          amount: double(7),
          operation: text("add_value"),
          slot: text("mainhand"),
        }),
        compound({
          type: text("minecraft:movement_speed"),
          id: text("nordtal:swift"),
          amount: double(0.1),
          operation: text("add_multiplied_base"),
        }),
      ]),
      "minecraft:unbreakable": compound({}),
      "minecraft:custom_model_data": compound({ floats: list([float(1)]), strings: list([text("lit")]) }),
      "minecraft:repair_cost": int(3),
      "minecraft:custom_data": compound({ PublicBukkitValues: compound({ "smp:marker": byte(1) }) }),
      "!minecraft:food": compound({}),
    })

    expect(detailsOf(item, names, TRANSLATIONS)).toEqual({
      name: "Frostbite",
      enchantments: ["Sharpness V", "Mending", "Unbreaking III"],
      lines: [
        { text: "Forged in the north", lore: true },
        { text: "Kills: 12", lore: true },
        { text: "+7 Attack damage in the main hand" },
        { text: "+10% Movement speed" },
        { text: "Unbreakable" },
        { text: "Custom model data 1, lit" },
        { text: "Repair cost 3" },
      ],
    })
  })

  it("names a pack item by its translation's fallback and lists its model", async () => {
    const item = await read("minecraft:rail", {
      "minecraft:item_model": text("nordtal:station_rail"),
      "minecraft:item_name": compound({
        translate: text("item.nordtal.station_rail"),
        fallback: text("Station Rail"),
      }),
      "minecraft:custom_data": compound({ PublicBukkitValues: compound({ "smp:station_rail": byte(1) }) }),
    })

    expect(detailsOf(item, names, TRANSLATIONS)).toEqual({
      name: "Station Rail",
      enchantments: [],
      lines: [{ text: "Item model nordtal:station_rail" }],
    })
  })

  it("shows a plain stack as its material alone, and an enchanted book's stored enchantments as badges", async () => {
    expect(detailsOf(await read("minecraft:oak_log"), names, TRANSLATIONS)).toEqual({ enchantments: [], lines: [] })
    const book = await read("minecraft:enchanted_book", {
      "minecraft:stored_enchantments": compound({ "minecraft:efficiency": int(12) }),
    })
    expect(detailsOf(book, names, TRANSLATIONS).enchantments).toEqual(["Efficiency 12"])
  })
})
