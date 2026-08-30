package eu.nordtal.s2.accessbot.model;

import lombok.Getter;

@Getter
public enum ContributionTier {
    SETTLER(3, "Settler", "1404916314669191178"),
    CITIZEN(5, "Citizen", "1400967326853238834"),
    KNIGHT(7, "Knight", "1400966995767464017"),
    LORD(9, "Lord", "1400967795969228820");

    private final int euroAmount;
    private final String displayName;
    private final String roleId;

    ContributionTier(final int euroAmount, final String displayName, final String roleId) {
        this.euroAmount = euroAmount;
        this.displayName = displayName;
        this.roleId = roleId;
    }

    public String selectLabel() {
        return String.format("%d€ - %s", euroAmount, displayName);
    }


}
