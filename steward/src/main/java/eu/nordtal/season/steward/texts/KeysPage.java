package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.time.Instant;

/** Signing in, holding a security key, the key list and a page that is not there. */
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
    MessageRef notSet(@Arg("setting") String setting);

    @Name("With Discord")
    MessageRef withDiscord();

    @Name("Signed in as")
    MessageRef signedInAs(@Arg("name") String name);

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
    MessageRef lastUsed(@Arg("at") Instant at);

    @Name("Registered")
    MessageRef registered(@Arg("at") Instant at);

    @Name("Rename")
    MessageRef rename(@Arg("key") String key);

    @Name("Remove")
    MessageRef remove(@Arg("key") String key);

    @Name("Not backed up")
    MessageRef notBackedUp();

    @Name("Second key")
    MessageRef secondKey();

    @Name("Add title")
    MessageRef addTitle();

    @Name("Add note")
    MessageRef addNote(@Arg("domain") String domain);

    @Name("Call this one")
    MessageRef callThisOne();

    @Name("Register")
    MessageRef register();

    @Name("Rename title")
    MessageRef renameTitle();

    @Name("New name")
    MessageRef newName(@Arg("key") String key);

    @Name("Remove title")
    MessageRef removeTitle(@Arg("key") String key);

    @Name("Remove note")
    MessageRef removeNote(@Arg("only") boolean only);

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

    @Name("Back to status")
    MessageRef backToStatus();
}
