package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.internalapi.agent.AgentWire.PluginGroup;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;

/** A service's page: its head, the console, Recreate and the plugins. */
@Name("Service page")
public interface ServicePage {

    @Name("Console")
    MessageRef console();

    @Name("Settings")
    MessageRef settings();

    @Name("Settings and translations")
    MessageRef settingsAndTexts();

    @Name("Plugins")
    MessageRef plugins();

    @Name("More actions")
    MessageRef moreActions();

    @Name("CPU")
    MessageRef cpu();

    @Name("RAM")
    MessageRef ram();

    @Name("Disk")
    MessageRef disk();

    @Name("Find")
    MessageRef find();

    @Name("Find in the console")
    MessageRef findIn();

    @Name("Close find")
    MessageRef closeFind();

    @Name("Lines")
    MessageRef lines();

    @Name("Line count")
    MessageRef lineCount(@Arg("lines") int lines);

    @Name("Download")
    MessageRef download();

    @Name("Going offline")
    MessageRef goingOffline();

    @Name("Offline")
    MessageRef offline();

    @Name("Log unreachable")
    MessageRef logUnreachable();

    @Name("Waiting for the log")
    MessageRef waitingForLog();

    @Name("Newest")
    MessageRef newest();

    @Name("Send a line")
    MessageRef sendLine();

    @Name("Send")
    MessageRef send();

    @Name("Not sent")
    MessageRef notSent();

    @Name("Recreate a service")
    MessageRef recreateService(@Arg("service") String service);

    @Name("Recreate title")
    MessageRef recreateTitle(@Arg("service") String service);

    @Name("Recreate note")
    MessageRef recreateNote();

    @Name("Recreate tip")
    MessageRef recreateTip(@Arg("service") String service);

    @Name("Agent silent")
    MessageRef agentSilent();

    @Name("Agent unknown")
    MessageRef agentUnknown();

    @Name("Agent not yet")
    MessageRef agentNotYet();

    @Name("Add plugin")
    MessageRef addPlugin();

    @Name("Check for updates")
    MessageRef checkUpdates();

    @Name("Uncheckable")
    MessageRef uncheckable();

    @Name("No volume")
    MessageRef noVolume();

    @Name("Nothing installed")
    MessageRef nothingInstalled();

    @Name("Plugin group")
    MessageRef group(@Arg("group") PluginGroup group);

    @Name("Not installed")
    MessageRef notInstalled();

    @Name("No build")
    MessageRef noBuild();

    @Name("No build for")
    MessageRef noBuildFor(@Arg("version") String version);

    @Name("Up to date")
    MessageRef upToDate();

    @Name("Held back")
    MessageRef heldBack();

    @Name("Update available")
    MessageRef updateAvailable();

    @Name("Installed by")
    MessageRef installedBy(@Arg("release") String release);

    @Name("Remove a plugin")
    MessageRef removePlugin(@Arg("name") String name);

    @Name("Remove title")
    MessageRef removeTitle(@Arg("name") String name);

    @Name("Remove")
    MessageRef remove();

    @Name("Not yet installed")
    MessageRef notYetInstalled();

    @Name("Remove the jar")
    MessageRef removeJar(@Arg("jar") String jar);

    @Name("Remove the jar and folder")
    MessageRef removeJarAndFolder(@Arg("jar") String jar, @Arg("folder") String folder);

    @Name("The jar")
    MessageRef theJar();

    @Name("Search Modrinth")
    MessageRef searchModrinth();

    @Name("Search placeholder")
    MessageRef searchPlaceholder();

    @Name("Nothing found")
    MessageRef nothingFound();

    @Name("Nothing on")
    MessageRef nothingOn(@Arg("loader") String loader, @Arg("version") String version);

    @Name("Given")
    MessageRef given();

    @Name("Given tip")
    MessageRef givenTip();

    @Name("Added")
    MessageRef added();

    @Name("Install")
    MessageRef install(@Arg("title") String title);

    @Name("Plugin added")
    MessageRef pluginAdded(@Arg("title") String title);

    @Name("Arrives")
    MessageRef arrives(@Arg("file") String file);

    @Name("Not added")
    MessageRef notAdded();

    @Name("On Modrinth")
    MessageRef onModrinth(@Arg("title") String title);
}
