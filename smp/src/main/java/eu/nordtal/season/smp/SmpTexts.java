package eu.nordtal.season.smp;

import eu.nordtal.season.messages.document.DocumentSpec;

/** The texts that several features of the SMP send. */
public final class SmpTexts {

    /** A feature that needs the Discord account refuses a player without one. */
    public static final DocumentSpec<DocumentSpec.None> NO_ACCOUNT_LINK = DocumentSpec.game(
                    "smp", "no-account-link", "Errors", DocumentSpec.Shape.CHAT)
            .titled("Account not linked");

    private SmpTexts() {}
}
