package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.Duration;
import java.time.Instant;

/** The texts for signing in, holding a security key, the key list and a missing page. */
@Name("Sign-in and keys")
public interface KeysPage {

    @Name("Brand")
    MessageRef brand();

    @Name("Season")
    MessageRef season();

    @Name("Sign in")
    MessageRef signIn();

    @Name("Nobody can sign in")
    MessageRef nobodyCanSignIn();

    @Name("Not set")
    MessageRef notSet(@Arg("setting") @Example("STEWARD_DISCORD_CLIENT_ID") String setting);

    @Name("With Discord")
    MessageRef withDiscord();

    @Name("Signed in as")
    MessageRef signedInAs(@Arg("name") @Example("Alex") String name);

    @Name("Hold title")
    MessageRef holdTitle();

    @Name("No keys here")
    MessageRef noKeysHere();

    @Name("No keys note")
    MessageRef noKeysNote();

    @Name("Browser cannot")
    MessageRef browserCannot();

    @Name("Waiting")
    MessageRef waiting();

    @Name("Use key")
    MessageRef useKey();

    @Name("Did not work")
    MessageRef didNotWork();

    @Name("Sign out instead")
    MessageRef signOutInstead();

    @Name("Setup title")
    MessageRef setupTitle();

    @Name("Call it")
    MessageRef callIt();

    @Name("Register this")
    MessageRef registerThis();

    @Name("Not registered")
    MessageRef notRegistered();

    @Name("My iPhone")
    MessageRef myIphone();

    @Name("My phone")
    MessageRef myPhone();

    @Name("My Mac")
    MessageRef myMac();

    @Name("My key")
    MessageRef myKey();

    @Name("Step-up title")
    MessageRef stepUpTitle();

    /** The sign-in and step-up title for an account with an authenticator app. */
    @Name("Either factor title")
    MessageRef eitherFactorTitle();

    @Name("Open directly")
    MessageRef openDirectly();

    @Name("Not accepted")
    MessageRef notAccepted();

    @Name("Not now")
    MessageRef notNow();

    @Name("Try again")
    MessageRef tryAgain();

    @Name("Hold key")
    MessageRef holdKey();

    @Name("Step-up lasts")
    MessageRef stepUpLasts(@Arg("length") @Example("PT15M") Duration length);

    @Name("Longer hold")
    MessageRef longerHold();

    @Name("Hold for")
    MessageRef holdFor(@Arg("length") @Example("PT15M") Duration length);

    @Name("Step-up holds")
    MessageRef stepUpHolds(@Arg("until") @Example("2026-10-23T18:55:00Z") Instant until);

    @Name("End step-up")
    MessageRef endStepUp();

    @Name("Only factor")
    MessageRef onlyFactor();

    @Name("App heading")
    MessageRef appHeading();

    @Name("No app")
    MessageRef noApp();

    @Name("Remove app")
    MessageRef removeApp();

    @Name("Remove app title")
    MessageRef removeAppTitle();

    @Name("Remove app note")
    MessageRef removeAppNote();

    @Name("App title")
    MessageRef appTitle();

    @Name("App scan")
    MessageRef appScan();

    @Name("App QR")
    MessageRef appQr();

    @Name("App secret")
    MessageRef appSecret();

    @Name("Open in app")
    MessageRef openInApp();

    @Name("Code label")
    MessageRef codeLabel();

    @Name("Confirm code")
    MessageRef confirmCode();

    @Name("Use code")
    MessageRef useCode();

    @Name("Checking")
    MessageRef checking();

    @Name("Or")
    MessageRef or();

    @Name("App instead")
    MessageRef appInstead();

    @Name("Closed")
    MessageRef closed();

    @Name("Not held")
    MessageRef notHeld();

    @Name("Another first")
    MessageRef anotherFirst();

    @Name("Heading")
    MessageRef heading();

    @Name("Add")
    MessageRef add();

    @Name("No key")
    MessageRef noKey();

    @Name("Last used")
    MessageRef lastUsed(@Arg("at") @Example("2026-10-21T18:40:00Z") Instant at);

    @Name("Registered")
    MessageRef registered(@Arg("at") @Example("2026-09-30T12:00:00Z") Instant at);

    @Name("Rename")
    MessageRef rename(@Arg("key") @Example("YubiKey") String key);

    @Name("Remove")
    MessageRef remove(@Arg("key") @Example("YubiKey") String key);

    @Name("Not backed up")
    MessageRef notBackedUp();

    @Name("Second key")
    MessageRef secondKey();

    @Name("Add title")
    MessageRef addTitle();

    @Name("Add note")
    MessageRef addNote(@Arg("domain") @Example("steward.nordtal.eu") String domain);

    @Name("Call this one")
    MessageRef callThisOne();

    @Name("Register")
    MessageRef register();

    @Name("Rename title")
    MessageRef renameTitle();

    @Name("New name")
    MessageRef newName(@Arg("key") @Example("YubiKey") String key);

    @Name("Remove title")
    MessageRef removeTitle(@Arg("key") @Example("YubiKey") String key);

    @Name("Remove note")
    MessageRef removeNote();

    @Name("Keep it")
    MessageRef keepIt();

    @Name("Remove it")
    MessageRef removeIt();

    @Name("Cancelled")
    MessageRef cancelled();

    @Name("Already registered")
    MessageRef alreadyRegistered();

    @Name("Aborted")
    MessageRef aborted();

    @Name("Wrong domain")
    MessageRef wrongDomain();

    @Name("Unsupported")
    MessageRef unsupported();

    @Name("Unexplained")
    MessageRef unexplained();

    @Name("Registration incomplete")
    MessageRef registrationIncomplete();

    @Name("Sign-in incomplete")
    MessageRef signInIncomplete();

    @Name("No key returned")
    MessageRef noKeyReturned();

    @Name("No answer")
    MessageRef noAnswer();

    @Name("Not found")
    MessageRef notFound();

    @Name("Back to overview")
    MessageRef backToOverview();
}
