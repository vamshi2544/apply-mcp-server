package dev.applymcp.prequal.tool;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the AI agent sees. Plain values, no internal codes, and a next step on every result.
 */
public final class ToolResults {

    private ToolResults() {
    }

    public record OffersResult(
            String decision,
            String prequalId,
            List<OfferView> offers,
            String reason,
            String nextStep) {
    }

    public record OfferView(
            String offerId,
            String productName,
            Integer creditLimit,
            BigDecimal purchaseApr,
            Instant expiresAt) {
    }

    public record AcceptResult(
            String outcome,
            String applicationId,
            String applicationStatus,
            String message,
            String nextStep) {
    }

    public record StatusResult(
            String applicationId,
            String status,
            String meaning,
            Instant updatedAt,
            String nextStep) {
    }
}
