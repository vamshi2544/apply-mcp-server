package dev.applymcp.prequal.backend;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory stand-in for the prequal APIs.
 *
 * Rules (so every path can be tested on purpose):
 * <ul>
 *   <li>annualIncome below 20,000 returns decision D1 (not qualified)</li>
 *   <li>ZIP 00000 returns decision R1 (needs more info)</li>
 *   <li>otherwise decision A1 with two offers</li>
 *   <li>last name "Expired" returns offers that have already expired (accept returns 410)</li>
 *   <li>last name "Slow" makes the first accept return 202 (in progress)</li>
 *   <li>last name "Verify" makes the application status V (needs verification)</li>
 *   <li>income 40,000 or more is approved (A); 20,000 to 39,999 stays pending (P)</li>
 * </ul>
 *
 * Idempotency on accept follows the production rules:
 * same key and same payload replays the first response (200); same key with a different
 * payload returns 409 IDEMPOTENCY_KEY_REUSED; a second acceptance for the same prequal
 * with a new key returns 409 ALREADY_ACCEPTED.
 */
@Component
@ConditionalOnProperty(name = "prequal.backend", havingValue = "mock", matchIfMissing = true)
public class MockPrequalBackend implements PrequalBackend {

    private final Clock clock;
    private final AtomicInteger prequalSeq = new AtomicInteger(1000);
    private final AtomicInteger applicationSeq = new AtomicInteger(55000);

    private final Map<String, PrequalRecord> prequals = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyRecord> idempotencyKeys = new ConcurrentHashMap<>();
    private final Map<String, PrequalApi.StatusResponse> applications = new ConcurrentHashMap<>();

    @Autowired
    public MockPrequalBackend() {
        this(Clock.systemUTC());
    }

    public MockPrequalBackend(Clock clock) {
        this.clock = clock;
    }

    @Override
    public BackendResponse<PrequalApi.OffersResponse> getOffers(PrequalApi.OffersRequest request, String correlationId) {
        if (request.consent() == null || !request.consent().softInquiry()) {
            return BackendResponse.error(400, "CONSENT_REQUIRED", "Soft inquiry consent is required");
        }
        PrequalApi.Applicant applicant = request.applicant();
        String prequalId = "PQ-" + prequalSeq.incrementAndGet();

        String decision;
        List<PrequalApi.Offer> offers = List.of();
        if (applicant.annualIncome() != null && applicant.annualIncome() < 20_000) {
            decision = "D1";
        } else if ("00000".equals(applicant.address().zip())) {
            decision = "R1";
        } else {
            decision = "A1";
            Instant expiresAt = "Expired".equalsIgnoreCase(applicant.lastName())
                    ? clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(1))
                    : clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(30));
            String base = prequalId.substring("PQ-".length());
            offers = List.of(
                    new PrequalApi.Offer("OF-" + base + "-1", "CB01", "Cashback Card", 3000,
                            new BigDecimal("27.99"), expiresAt),
                    new PrequalApi.Offer("OF-" + base + "-2", "TR01", "Travel Card", 5000,
                            new BigDecimal("25.49"), expiresAt));
        }

        prequals.put(prequalId, new PrequalRecord(prequalId, applicant, offers));
        return BackendResponse.ok(200, new PrequalApi.OffersResponse(prequalId, decision, offers));
    }

    @Override
    public synchronized BackendResponse<PrequalApi.AcceptResponse> acceptOffer(String offerId,
                                                                               PrequalApi.AcceptRequest request,
                                                                               String idempotencyKey,
                                                                               String correlationId) {
        String fingerprint = request.prequalId() + "|" + offerId;

        IdempotencyRecord previous = idempotencyKeys.get(idempotencyKey);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                return BackendResponse.error(409, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used with a different payload");
            }
            // Replay: return the original result. A previous 202 has completed by now.
            return BackendResponse.ok(200, previous.response());
        }

        PrequalRecord prequal = prequals.get(request.prequalId());
        if (prequal == null) {
            return BackendResponse.error(404, "PREQUAL_NOT_FOUND", "Prequal id not found");
        }
        PrequalApi.Offer offer = prequal.offers().stream()
                .filter(o -> o.offerId().equals(offerId))
                .findFirst()
                .orElse(null);
        if (offer == null) {
            return BackendResponse.error(404, "OFFER_NOT_FOUND", "Offer not found for this prequal");
        }
        if (offer.expiresAt().isBefore(clock.instant())) {
            return BackendResponse.error(410, "OFFER_EXPIRED", "Offer has expired");
        }
        if (prequal.acceptedApplicationId() != null) {
            return BackendResponse.error(409, "ALREADY_ACCEPTED",
                    "An offer from this prequal was already accepted. applicationId="
                            + prequal.acceptedApplicationId());
        }

        String applicationId = "APP-" + applicationSeq.incrementAndGet();
        String statusCd = statusFor(prequal.applicant());
        PrequalApi.AcceptResponse response = new PrequalApi.AcceptResponse(applicationId, statusCd);

        prequals.put(prequal.prequalId(), prequal.withAcceptedApplication(applicationId));
        idempotencyKeys.put(idempotencyKey, new IdempotencyRecord(fingerprint, response));
        applications.put(applicationId, new PrequalApi.StatusResponse(applicationId, statusCd, clock.instant()));

        boolean slow = "Slow".equalsIgnoreCase(prequal.applicant().lastName());
        return BackendResponse.ok(slow ? 202 : 201, response);
    }

    @Override
    public BackendResponse<PrequalApi.StatusResponse> getStatus(String applicationId, String correlationId) {
        PrequalApi.StatusResponse status = applications.get(applicationId);
        if (status == null) {
            return BackendResponse.error(404, "APPLICATION_NOT_FOUND", "Application not found");
        }
        return BackendResponse.ok(200, status);
    }

    private static String statusFor(PrequalApi.Applicant applicant) {
        if ("Verify".equalsIgnoreCase(applicant.lastName())) {
            return "V";
        }
        Integer income = applicant.annualIncome();
        return income != null && income >= 40_000 ? "A" : "P";
    }

    private record PrequalRecord(String prequalId, PrequalApi.Applicant applicant,
                                 List<PrequalApi.Offer> offers, String acceptedApplicationId) {

        PrequalRecord(String prequalId, PrequalApi.Applicant applicant, List<PrequalApi.Offer> offers) {
            this(prequalId, applicant, offers, null);
        }

        PrequalRecord withAcceptedApplication(String applicationId) {
            return new PrequalRecord(prequalId, applicant, offers, Objects.requireNonNull(applicationId));
        }
    }

    private record IdempotencyRecord(String fingerprint, PrequalApi.AcceptResponse response) {
    }
}
