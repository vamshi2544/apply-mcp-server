package dev.applymcp.prequal.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockPrequalBackendTest {

    private static final String KEY_1 = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String KEY_2 = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";

    private MockPrequalBackend backend;

    @BeforeEach
    void setUp() {
        backend = new MockPrequalBackend(Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void qualifiedApplicantGetsTwoOffers() {
        var response = backend.getOffers(request("Doe", "45402", 85_000), "c1");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().decisionCd()).isEqualTo("A1");
        assertThat(response.body().offers()).hasSize(2);
    }

    @Test
    void lowIncomeIsNotQualified() {
        var response = backend.getOffers(request("Doe", "45402", 15_000), "c1");
        assertThat(response.body().decisionCd()).isEqualTo("D1");
        assertThat(response.body().offers()).isEmpty();
    }

    @Test
    void zipZeroNeedsMoreInfo() {
        var response = backend.getOffers(request("Doe", "00000", 85_000), "c1");
        assertThat(response.body().decisionCd()).isEqualTo("R1");
    }

    @Test
    void acceptCreatesApplicationOnce() {
        var offers = backend.getOffers(request("Doe", "45402", 85_000), "c1").body();
        String offerId = offers.offers().get(0).offerId();

        var first = backend.acceptOffer(offerId, accept(offers.prequalId()), KEY_1, "c2");
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().statusCd()).isEqualTo("A");

        var replay = backend.acceptOffer(offerId, accept(offers.prequalId()), KEY_1, "c3");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().applicationId()).isEqualTo(first.body().applicationId());
    }

    @Test
    void sameKeyDifferentOfferIsConflict() {
        var offers = backend.getOffers(request("Doe", "45402", 85_000), "c1").body();
        backend.acceptOffer(offers.offers().get(0).offerId(), accept(offers.prequalId()), KEY_1, "c2");

        var conflict = backend.acceptOffer(offers.offers().get(1).offerId(), accept(offers.prequalId()), KEY_1, "c3");
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void newKeyForAlreadyAcceptedPrequalIsConflict() {
        var offers = backend.getOffers(request("Doe", "45402", 85_000), "c1").body();
        backend.acceptOffer(offers.offers().get(0).offerId(), accept(offers.prequalId()), KEY_1, "c2");

        var second = backend.acceptOffer(offers.offers().get(1).offerId(), accept(offers.prequalId()), KEY_2, "c3");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.errorCode()).isEqualTo("ALREADY_ACCEPTED");
    }

    @Test
    void expiredOfferIsGone() {
        var offers = backend.getOffers(request("Expired", "45402", 85_000), "c1").body();
        var response = backend.acceptOffer(offers.offers().get(0).offerId(), accept(offers.prequalId()), KEY_1, "c2");
        assertThat(response.status()).isEqualTo(410);
    }

    @Test
    void slowApplicantFirstAcceptIsInProgressThenReplays() {
        var offers = backend.getOffers(request("Slow", "45402", 85_000), "c1").body();
        String offerId = offers.offers().get(0).offerId();
        assertThat(backend.acceptOffer(offerId, accept(offers.prequalId()), KEY_1, "c2").status()).isEqualTo(202);
        assertThat(backend.acceptOffer(offerId, accept(offers.prequalId()), KEY_1, "c3").status()).isEqualTo(200);
    }

    @Test
    void statusReflectsIncomeAndVerification() {
        var pending = backend.getOffers(request("Doe", "45402", 30_000), "c1").body();
        var app = backend.acceptOffer(pending.offers().get(0).offerId(), accept(pending.prequalId()), KEY_1, "c2").body();
        assertThat(backend.getStatus(app.applicationId(), "c3").body().statusCd()).isEqualTo("P");

        var verify = backend.getOffers(request("Verify", "45402", 85_000), "c4").body();
        var app2 = backend.acceptOffer(verify.offers().get(0).offerId(), accept(verify.prequalId()), KEY_2, "c5").body();
        assertThat(backend.getStatus(app2.applicationId(), "c6").body().statusCd()).isEqualTo("V");
    }

    @Test
    void unknownApplicationIsNotFound() {
        assertThat(backend.getStatus("APP-1", "c1").status()).isEqualTo(404);
    }

    private static PrequalApi.OffersRequest request(String lastName, String zip, int income) {
        return new PrequalApi.OffersRequest(
                new PrequalApi.Applicant("Jane", lastName, LocalDate.of(1990, 4, 12), "6789",
                        new PrequalApi.Address("100 Main St", "Dayton", "OH", zip), income),
                new PrequalApi.Consent(true, Instant.parse("2026-10-02T12:00:00Z")));
    }

    private static PrequalApi.AcceptRequest accept(String prequalId) {
        return new PrequalApi.AcceptRequest(prequalId, Instant.parse("2026-10-02T12:01:00Z"));
    }
}
