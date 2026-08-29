package eu.nordtal.s2.paymentsbot.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.ObjectUtils;

public @Getter
@Setter
@AllArgsConstructor
class SetupFlow {
    private final String flowMessageId;
    private String initiatorId;
    private String receiverId;
    private PaymentMethod paymentMethod;
    private ContributionTier contributionTier;

    public boolean isComplete() {
        return ObjectUtils.allNotNull(initiatorId, receiverId, paymentMethod, contributionTier);
    }
}
