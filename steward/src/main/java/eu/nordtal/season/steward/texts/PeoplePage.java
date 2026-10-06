package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.access.AccessSource;
import eu.nordtal.season.database.access.MemberState;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.time.Duration;
import java.time.Instant;

/** The Users page and one person's page: access, periods, roles and the writes on them. */
@Name("People")
public interface PeoplePage {

    @Name("Title")
    MessageRef title();

    @Name("Person column")
    MessageRef person();

    @Name("Access column")
    MessageRef access();

    @Name("Access column tip")
    MessageRef accessTip();

    @Name("Minecraft")
    MessageRef minecraft();

    @Name("Roles column")
    MessageRef roles();

    @Name("Playtime")
    MessageRef playtime();

    @Name("Active until")
    MessageRef activeUntil(@Arg("until") Instant until);

    @Name("Active tip")
    MessageRef activeTip(@Arg("until") Instant until);

    @Name("Never")
    MessageRef never();

    @Name("Never tip")
    MessageRef neverTip();

    @Name("No access")
    MessageRef noAccess();

    @Name("No access tip")
    MessageRef noAccessTip(@Arg("until") Instant until);

    @Name("Expired")
    MessageRef expired(@Arg("at") Instant at);

    @Name("Expired tip")
    MessageRef expiredTip();

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

    @Name("Nobody")
    MessageRef nobody();

    @Name("Nobody note")
    MessageRef nobodyNote();

    @Name("No match")
    MessageRef noMatch();

    @Name("No match note")
    MessageRef noMatchNote(@Arg("withAccess") boolean withAccess);

    @Name("Supporter")
    MessageRef supporter();

    @Name("Supporter tip")
    MessageRef supporterTip();

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

    @Name("Membership tip")
    MessageRef memberTip(@Arg("state") MemberState state);

    @Name("Source")
    MessageRef source(@Arg("source") AccessSource source);

    @Name("Revoked")
    MessageRef revoked(@Arg("at") Instant at);

    @Name("Revoked tip")
    MessageRef revokedTip();

    @Name("Begins")
    MessageRef begins(@Arg("at") Instant at);

    @Name("Begins tip")
    MessageRef beginsTip();

    @Name("Running")
    MessageRef running();

    @Name("Running tip")
    MessageRef runningTip();

    @Name("Over")
    MessageRef over();

    @Name("Over tip")
    MessageRef overTip();

    @Name("Grant access")
    MessageRef grantAccess();

    @Name("Grant title")
    MessageRef grantTitle();

    @Name("Grant note")
    MessageRef grantNote();

    @Name("Granted")
    MessageRef granted();

    @Name("Valid until")
    MessageRef validUntil(@Arg("until") Instant until);

    @Name("Not granted")
    MessageRef notGranted();

    @Name("Discord id")
    MessageRef discordId();

    @Name("Id example")
    MessageRef idExample();

    @Name("Most days")
    MessageRef mostDays(@Arg("most") int most);

    @Name("A day")
    MessageRef dayIsDay();

    @Name("Appended")
    MessageRef appended();

    @Name("From launch")
    MessageRef fromLaunch();

    @Name("Unknown id")
    MessageRef unknownId();

    @Name("Play time title")
    MessageRef playtimeTitle();

    @Name("Play time note")
    MessageRef playtimeNote(@Arg("name") String name);

    @Name("Play time set")
    MessageRef playtimeSet(@Arg("name") String name);

    @Name("Play time from")
    MessageRef playtimeFrom(@Arg("time") Duration time);

    @Name("Play time not written")
    MessageRef playtimeNotWritten();

    @Name("Hours")
    MessageRef hours();

    @Name("Minutes")
    MessageRef minutes();

    @Name("Counted")
    MessageRef counted(
            @Arg("counted") Duration counted, @Arg("becoming") Duration becoming, @Arg("usable") boolean usable);

    @Name("Unlink title")
    MessageRef unlinkTitle();

    @Name("Unlink note")
    MessageRef unlinkNote();

    @Name("Nothing to unlink")
    MessageRef nothingToUnlink();

    @Name("None linked")
    MessageRef noneLinked();

    @Name("Unlinked")
    MessageRef unlinked();

    @Name("Journal names you")
    MessageRef journalNamesYou();

    @Name("Not unlinked")
    MessageRef notUnlinked();

    @Name("Pack title")
    MessageRef packTitle(@Arg("exempted") boolean exempted, @Arg("name") String name);

    @Name("Pack note")
    MessageRef packNote(@Arg("exempted") boolean exempted);

    @Name("Pack changed")
    MessageRef packChanged(@Arg("exempted") boolean exempted);

    @Name("Nothing changed")
    MessageRef nothingChanged();

    @Name("Make admin title")
    MessageRef makeAdminTitle(@Arg("name") String name);

    @Name("Make admin note")
    MessageRef makeAdminNote();

    @Name("Not made admin")
    MessageRef notMadeAdmin();

    @Name("Revoke admin title")
    MessageRef revokeAdminTitle(@Arg("name") String name);

    @Name("Revoke admin note")
    MessageRef revokeAdminNote(@Arg("branch") int branch);

    @Name("No longer admin")
    MessageRef noLongerAdmin();

    @Name("Below too")
    MessageRef belowToo(@Arg("count") int count);

    @Name("Not revoked")
    MessageRef notRevoked();

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

    @Name("No refund")
    MessageRef noRefund();

    @Name("Entry stays")
    MessageRef entryStays();

    @Name("Guild")
    MessageRef guild();

    @Name("Unknown person")
    MessageRef unknownPerson();

    @Name("Unknown person note")
    MessageRef unknownPersonNote(@Arg("id") String id);

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

    @Name("No period note")
    MessageRef noPeriodNote();

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
}
