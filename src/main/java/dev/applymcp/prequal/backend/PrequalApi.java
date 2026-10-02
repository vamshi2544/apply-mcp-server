package dev.applymcp.prequal.backend;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Backend (REST API) contract for the prequal APIs.
 *
 * These records mirror the existing APIs exactly, including their internal codes.
 * They are never exposed to the AI agent; the tool layer translates them.
 */
public final class PrequalApi {

    private PrequalApi() {
    }

    // POST /v1/prequal/offers
    public record OffersRequest(Applicant applicant, Consent consent) {
    }

    public record Applicant(String firstName, String lastName, LocalDate dob, String ssnLast4,
                            Address address, Integer annualIncome) {
    }

    public record Address(String line1, String city, String state, String zip) {
    }

    public record Consent(boolean softInquiry, Instant timestamp) {
    }

    /** decisionCd: A1 = qualified, D1 = not qualified, R1 = needs more info. */
    public record OffersResponse(String prequalId, String decisionCd, List<Offer> offers) {
    }

    public record Offer(String offerId, String productCd, String productName, Integer creditLine,
                        BigDecimal purchaseApr, Instant expiresAt) {
    }

    // POST /v1/prequal/offers/{offerId}/accept   (header: Idempotency-Key)
    public record AcceptRequest(String prequalId, Instant acceptedAt) {
    }

    /** statusCd: P = pending, A = approved, D = declined, V = needs verification. */
    public record AcceptResponse(String applicationId, String statusCd) {
    }

    // GET /v1/applications/{applicationId}/status
    public record StatusResponse(String applicationId, String statusCd, Instant updatedAt) {
    }

    public record ApiError(String code, String message) {
    }
}
