package eu.nordtal.s2.accessbot.model;

import eu.nordtal.s2.accessbot.service.BunqService;
import lombok.Getter;

import java.util.function.Function;

@Getter
public enum PaymentMethod {
    CARD("Credit or Debit card", setupFlow -> BunqService.createPaymentLink(setupFlow.getInitiatorId(), setupFlow.getReceiverId(), setupFlow.getContributionTier().getEuroAmount())),
    BANK_TRANSFER("Bank transfer", setupFlow -> String.format("""
            IBAN: `DE48370190001011230838`
            Name: `T. Hoffmann & J. Kötz`
            Amount: %d€
            Description: `%s` (don't change!)
            """, setupFlow.getContributionTier().getEuroAmount(), setupFlow.getInitiatorId() + ":" + setupFlow.getReceiverId()));

    private final String displayName;
    private final Function<SetupFlow, String> detailsGenerator;

    PaymentMethod(final String displayName, final Function<SetupFlow, String> detailsGenerator) {
        this.displayName = displayName;
        this.detailsGenerator = detailsGenerator;
    }
}
