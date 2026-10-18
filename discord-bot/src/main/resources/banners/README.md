# Banner images

`contribution.png`, `link.png` and `onboarding.png` are placeholder images of 2560x640 pixels. The bot attaches them
to its managed messages as `attachment://contribution.png`, `attachment://link.png` and `attachment://onboarding.png`.
The onboarding image belongs to the welcome in the onboarding channel.

To change a banner, replace the file under the same name. `ManagedMessage` loads the files from the classpath by name.
