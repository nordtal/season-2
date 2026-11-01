package eu.nordtal.season.smp.config;

import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.settings.SettingRefused;

/** The checks of smp's groups for what their specs cannot declare. */
public final class SmpSettings {

    private SmpSettings() {}

    /** Throws {@link SettingRefused} for a broken structure. It checks neither names nor stored progress. */
    public static void checkMilestones(final MilestonesSpec config) {
        final Milestones.Result result = Milestones.read(config);
        if (!result.problems().isEmpty()) {
            throw new SettingRefused(
                    "milestones", SettingRule.TRACK, "the milestone track is not usable:\n" + result.describe());
        }
    }
}
