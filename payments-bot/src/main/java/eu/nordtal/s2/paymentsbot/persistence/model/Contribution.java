package eu.nordtal.s2.paymentsbot.persistence.model;

import eu.nordtal.s2.paymentsbot.model.ContributionTier;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "contribution")
@NamedQuery(
        name = "Contribution.findByReceiver",
        query = """
                  select c
                  from Contribution c
                  where c.receiverId = :receiverId
                  order by c.created asc, c.euroAmount desc
                """
)
@NamedQuery(
        name = "Contribution.allProcessedPaymentIds",
        query = "SELECT contribution.bunqPaymentId FROM Contribution contribution"
)
public class Contribution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String receiverId;

    private float euroAmount;

    private LocalDateTime created;

    private long durationSeconds;

    private long bunqPaymentId;

    public ContributionTier contributionTier() {
        return Arrays.stream(ContributionTier.values())
                .filter(tier -> tier.getEuroAmount() <= euroAmount)
                .max(Comparator.comparingInt(ContributionTier::getEuroAmount))
                .orElse(null);
    }


}
