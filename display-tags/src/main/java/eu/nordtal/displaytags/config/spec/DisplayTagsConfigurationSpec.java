package eu.nordtal.displaytags.config.spec;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import eu.nordtal.jcore.config.spec.annotation.Save;

@ConfigSpec(
        header = {
            "----------------------------------",
            "        DisplayTags Config        ",
            "----------------------------------",
            "This is the configuration file for DisplayTags.",
            "To apply any changes you make here, run: /displaytags reload",
            "Docs: https://github.com/nordtal/papermc-display-tags"
        })
public interface DisplayTagsConfigurationSpec {
    @Order(1)
    @Key("nametag")
    NameTagConfigurationSpec nametag();

    @Save
    void save();

    @Reload
    void reload();
}
