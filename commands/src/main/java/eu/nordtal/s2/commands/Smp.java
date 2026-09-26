package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;
import eu.nordtal.s2.common.message.context.MilestoneContext;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Name;

/** The SMP's own messages: access status and the admin commands around it. */
@Name("SMP")
public interface Smp {

    Access access();

    @Name("Access")
    interface Access {

        @Name("Active")
        MessageRef active(@Arg("until") Object until);

        @Name("Expired")
        MessageRef expired(@Arg("since") Object since);

        @Name("Failed")
        MessageRef failed();

        @Name("Linked")
        MessageRef linked(@Arg("player") PlayerContext player, @Arg("discord") DiscordMemberContext discord);

        @Name("Never")
        MessageRef never();

        @Name("No payment")
        MessageRef noPayment();

        @Name("Payment")
        MessageRef payment(
                @Arg("reference") Object reference,
                @Arg("days") Object days,
                @Arg("amount") Object amount,
                @Arg("since") Object since);

        @Name("Payment unknown")
        MessageRef paymentUnknown();

        @Name("Payment unstarted")
        MessageRef paymentUnstarted(
                @Arg("reference") Object reference, @Arg("days") Object days, @Arg("since") Object since);

        @Name("Unlinked")
        MessageRef unlinked(@Arg("player") PlayerContext player);
    }

    Admin admin();

    @Name("Admin")
    interface Admin {

        @Name("Aura changed")
        MessageRef auraChanged(@Arg("player") PlayerContext player, @Arg("delta") Object delta);

        @Name("Aura unknown")
        MessageRef auraUnknown(@Arg("player") PlayerContext player, @Arg("delta") Object delta);

        @Name("Milestone unlocked")
        MessageRef milestoneUnlocked(@Arg("key") Object key);

        @Name("No active milestone")
        MessageRef noActiveMilestone();

        @Name("No such objective")
        MessageRef noSuchObjective();

        @Name("Player offline")
        MessageRef playerOffline();

        @Name("Reloaded")
        MessageRef reloaded();

        @Name("Track refused")
        MessageRef trackRefused(@Arg("problems") Object problems);

        @Name("Target unlinked")
        MessageRef targetUnlinked(@Arg("player") PlayerContext player);

        @Name("Objective completed")
        MessageRef objectiveCompleted(@Arg("key") Object key, @Arg("milestone") MilestoneContext milestone);

        @Name("Reload failed")
        MessageRef reloadFailed();

        @Name("Read failed")
        MessageRef readFailed();
    }
}
