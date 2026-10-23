package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.access.AccessSource;
import eu.nordtal.season.database.access.MemberState;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.mode.ModeStateStore.Status;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.time.Duration;
import java.time.Instant;

/** The texts of the Users page and of the page of one person for access, periods, roles and their changes. */
@Name("People")
public interface PeoplePage {

    @Name("Title")
    MessageRef title();

    @Name("Person column")
    MessageRef person();

    @Name("Access column")
    MessageRef access();

    @Name("Minecraft")
    MessageRef minecraft();

    @Name("Roles column")
    MessageRef roles();

    @Name("Playtime")
    MessageRef playtime();

    @Name("Aura")
    MessageRef aura();

    @Name("Spins")
    MessageRef spins();

    @Name("Last seen column")
    MessageRef lastSeen();

    @Name("Online now")
    MessageRef online();

    @Name("Seen at")
    MessageRef seenAt(@Arg("at") Instant at);

    @Name("Never seen")
    MessageRef neverSeen();

    @Name("Active until")
    MessageRef activeUntil(@Arg("until") Instant until);

    @Name("Exact time tip")
    MessageRef exactTip(@Arg("at") Instant at);

    @Name("Never")
    MessageRef never();

    @Name("No access")
    MessageRef noAccess();

    @Name("No access tip")
    MessageRef noAccessTip(@Arg("until") Instant until);

    @Name("Expired")
    MessageRef expired(@Arg("at") Instant at);

    @Name("Not linked")
    MessageRef notLinked();

    @Name("Not linked tip")
    MessageRef notLinkedTip(@Arg("paid") boolean paid);

    @Name("Filter")
    MessageRef filter();

    @Name("Filter name")
    MessageRef filterName();

    @Name("With access only")
    MessageRef withAccess();

    @Name("Admins")
    MessageRef admins();

    @Name("Supporters")
    MessageRef supporters();

    @Name("Linked only")
    MessageRef onlyLinked();

    @Name("Not linked only")
    MessageRef onlyUnlinked();

    @Name("Nobody")
    MessageRef nobody();

    @Name("No match")
    MessageRef noMatch();

    @Name("Supporter")
    MessageRef supporter();

    @Name("Admin")
    MessageRef admin();

    @Name("Root admin")
    MessageRef rootAdmin();

    @Name("Granted by")
    MessageRef grantedBy(@Arg("name") String name);

    @Name("No resource pack")
    MessageRef noPack();

    @Name("No resource pack tip")
    MessageRef noPackTip(@Arg("by") String by, @Arg("at") Instant at);

    @Name("Some admin")
    MessageRef someAdmin();

    @Name("Actions for")
    MessageRef actionsFor(@Arg("name") String name);

    @Name("Count")
    MessageRef count(@Arg("count") int count);

    @Name("Range")
    MessageRef range(@Arg("from") int from, @Arg("to") int to, @Arg("total") int total);

    @Name("Previous")
    MessageRef previous();

    @Name("Next")
    MessageRef next();

    @Name("Periods")
    MessageRef periods();

    @Name("Grant")
    MessageRef grant();

    @Name("Revoke")
    MessageRef revoke();

    @Name("Unlink")
    MessageRef unlink();

    @Name("Make admin")
    MessageRef makeAdmin();

    @Name("Revoke admin")
    MessageRef revokeAdmin();

    @Name("Pack action")
    MessageRef pack(@Arg("exempted") boolean exempted);

    @Name("Membership")
    MessageRef member(@Arg("state") MemberState state);

    @Name("Source")
    MessageRef source(@Arg("source") AccessSource source);

    @Name("Revoked")
    MessageRef revoked(@Arg("at") Instant at);

    @Name("Begins")
    MessageRef begins(@Arg("at") Instant at);

    @Name("Running")
    MessageRef running();

    @Name("Over")
    MessageRef over();

    @Name("Grant access")
    MessageRef grantAccess();

    @Name("Grant title")
    MessageRef grantTitle(@Arg("name") String name);

    @Name("Granted")
    MessageRef granted();

    @Name("Valid until")
    MessageRef validUntil(@Arg("until") Instant until);

    @Name("Most days")
    MessageRef mostDays(@Arg("most") int most);

    @Name("Play time title")
    MessageRef playtimeTitle();

    @Name("Play time set")
    MessageRef playtimeSet(@Arg("name") String name);

    @Name("Play time from")
    MessageRef playtimeFrom(@Arg("time") Duration time);

    @Name("Aura title")
    MessageRef auraTitle();

    @Name("Aura set")
    MessageRef auraSet(@Arg("name") String name, @Arg("aura") int aura);

    @Name("Aura now")
    MessageRef auraNow(@Arg("aura") int aura);

    @Name("Spins title")
    MessageRef spinsTitle();

    @Name("Spins set")
    MessageRef spinsSet(@Arg("name") String name, @Arg("spins") int spins);

    @Name("Spins now")
    MessageRef spinsNow(@Arg("spins") int spins);

    @Name("Hours")
    MessageRef hours();

    @Name("Minutes")
    MessageRef minutes();

    @Name("Counted")
    MessageRef counted(
            @Arg("counted") Duration counted, @Arg("becoming") Duration becoming, @Arg("usable") boolean usable);

    @Name("Unlink title")
    MessageRef unlinkTitle();

    @Name("Nothing to unlink")
    MessageRef nothingToUnlink();

    @Name("None linked")
    MessageRef noneLinked();

    @Name("Unlinked")
    MessageRef unlinked();

    @Name("Pack title")
    MessageRef packTitle(@Arg("exempted") boolean exempted, @Arg("name") String name);

    @Name("Pack changed")
    MessageRef packChanged(@Arg("exempted") boolean exempted);

    @Name("Make admin title")
    MessageRef makeAdminTitle(@Arg("name") String name);

    @Name("Revoke admin title")
    MessageRef revokeAdminTitle(@Arg("name") String name);

    @Name("Revoke admin note")
    MessageRef revokeAdminNote(@Arg("branch") int branch);

    @Name("No longer admin")
    MessageRef noLongerAdmin();

    @Name("Below too")
    MessageRef belowToo(@Arg("count") int count);

    @Name("Revoke title")
    MessageRef revokeTitle();

    @Name("Revoke note")
    MessageRef revokeNote();

    @Name("Nothing to revoke")
    MessageRef nothingToRevoke();

    @Name("None running")
    MessageRef noneRunning();

    @Name("Revoked for")
    MessageRef revokedFor(@Arg("count") int count);

    @Name("Thrown out")
    MessageRef thrownOut();

    @Name("Guild")
    MessageRef guild();

    @Name("Unknown person")
    MessageRef unknownPerson();

    @Name("No payment request")
    MessageRef noPayment();

    @Name("No journal entry")
    MessageRef noEntry();

    @Name("Linked at")
    MessageRef linkedAt(@Arg("at") Instant at);

    @Name("Language")
    MessageRef language();

    @Name("Last changed")
    MessageRef lastChanged(@Arg("at") Instant at);

    @Name("No period")
    MessageRef noPeriod();

    @Name("Source column")
    MessageRef sourceColumn();

    @Name("Window")
    MessageRef window();

    @Name("State")
    MessageRef state();

    @Name("Request")
    MessageRef request();

    @Name("Request gone")
    MessageRef requestGone(@Arg("source") AccessSource source);

    @Name("Inventories")
    MessageRef inventories();

    @Name("No inventory")
    MessageRef noInventory();

    @Name("Inventory server")
    MessageRef inventoryServer();

    @Name("Game mode")
    MessageRef mode(@Arg("mode") Mode mode);

    @Name("Inventory status")
    MessageRef inventoryStatus(@Arg("status") Status status, @Arg("server") String server);

    @Name("Level")
    MessageRef level(@Arg("level") int level);

    @Name("Armour and offhand")
    MessageRef armour();

    @Name("Main inventory")
    MessageRef mainInventory();

    @Name("Hotbar")
    MessageRef hotbar();

    @Name("Ender chest")
    MessageRef enderChest();

    @Name("Stack")
    MessageRef stack(@Arg("item") String item, @Arg("amount") int amount);

    @Name("Remove stack")
    MessageRef removeStack(@Arg("item") String item, @Arg("amount") int amount);

    @Name("Remove stack loss")
    MessageRef removeStackLoss(@Arg("server") String server);

    @Name("Stack removed")
    MessageRef stackRemoved(@Arg("item") String item, @Arg("amount") int amount);
}
