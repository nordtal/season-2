package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.Instant;

/** The texts of the notifications dialog: this device, the alert types, the thresholds and the devices. */
@Name("Notifications")
public interface NotificationsPanel {

    @Name("Title")
    MessageRef title();

    @Name("No push")
    MessageRef noPush();

    @Name("This device")
    MessageRef thisDevice();

    @Name("On")
    MessageRef on();

    @Name("Off")
    MessageRef off();

    @Name("Turn on")
    MessageRef turnOn();

    @Name("Turn off")
    MessageRef turnOff();

    @Name("Notify about")
    MessageRef notifyAbout();

    @Name("Alert type")
    MessageRef type(@Arg("type") AlertType type);

    @Name("Sample")
    MessageRef sample(@Arg("type") AlertType type);

    @Name("Push")
    MessageRef push();

    @Name("Discord")
    MessageRef discord();

    @Name("By channel")
    MessageRef byChannel(
            @Arg("type") @Example("Payments") String type, @Arg("channel") @Example("Push") String channel);

    @Name("Tell me when")
    MessageRef tellMeWhen();

    @Name("Disk in use")
    MessageRef diskInUse();

    @Name("Memory in use")
    MessageRef memoryInUse();

    @Name("Newest backup")
    MessageRef newestBackup();

    @Name("Read only")
    MessageRef readOnly(
            @Arg("group") @Example("Web Push") String group, @Arg("service") @Example("steward") String service);

    @Name("Devices")
    MessageRef devices();

    @Name("No device")
    MessageRef noDevice();

    @Name("Unnamed")
    MessageRef unnamed();

    @Name("This one")
    MessageRef thisOne();

    @Name("Last notified")
    MessageRef lastNotified(@Arg("at") @Example("2026-10-23T17:10:00Z") Instant at);

    @Name("Added")
    MessageRef added(@Arg("at") @Example("2026-10-01T12:00:00Z") Instant at);

    @Name("Send test")
    MessageRef sendTest(@Arg("name") @Example("Firefox on Android") String name);

    @Name("Test notifications")
    MessageRef testNotifications();

    @Name("Remove device")
    MessageRef removeDevice(@Arg("name") @Example("Firefox on Android") String name);
}
