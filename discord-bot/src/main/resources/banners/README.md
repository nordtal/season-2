# Banner images

`contribution.png`, `hunger-games.png`, `link.png`, `onboarding.png` and `rules.png` are the Nordtal banner, 2560x640 pixels.
Each heads one managed message: the contribution message, the Register message of the Hunger Games, the link
message, the welcome in the onboarding channel and the rules. A document names its file as `attachment://<name>`, and the bot
attaches the file to the message.

To change a banner, replace the file under the same name. `DocumentRenderer` loads the files from the classpath by name.
