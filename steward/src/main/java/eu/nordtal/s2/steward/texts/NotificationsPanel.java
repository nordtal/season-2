package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;
import java.time.Instant;

/** The notifications dialog: this device, the alert types per channel, the thresholds and the devices. */
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
    MessageRef byChannel(@Arg("type") String type, @Arg("channel") String channel);

    @Name("Tell me when")
    MessageRef tellMeWhen();

    @Name("Disk in use")
    MessageRef diskInUse();

    @Name("Memory in use")
    MessageRef memoryInUse();

    @Name("Newest backup")
    MessageRef newestBackup();

    @Name("Read only")
    MessageRef readOnly(@Arg("group") String group, @Arg("service") String service);

    @Name("Devices")
    MessageRef devices();

    @Name("No device")
    MessageRef noDevice();

    @Name("Unnamed")
    MessageRef unnamed();

    @Name("This one")
    MessageRef thisOne();

    @Name("Last notified")
    MessageRef lastNotified(@Arg("at") Instant at);

    @Name("Added")
    MessageRef added(@Arg("at") Instant at);

    @Name("Send test")
    MessageRef sendTest(@Arg("name") String name);

    @Name("Test notifications")
    MessageRef testNotifications();

    @Name("Remove device")
    MessageRef removeDevice(@Arg("name") String name);
}
