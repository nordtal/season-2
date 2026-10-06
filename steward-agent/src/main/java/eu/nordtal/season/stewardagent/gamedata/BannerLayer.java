package eu.nordtal.season.stewardagent.gamedata;

/**
 * One pattern of a banner: the texture that masks it and the dye that tints it.
 *
 * @param texture the pattern's texture id, such as {@code minecraft:entity/banner/rhombus}
 * @param dye the dye's name, such as {@code cyan}
 */
record BannerLayer(String texture, String dye) {}
