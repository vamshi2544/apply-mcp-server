package dev.applymcp.prequal.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import dev.applymcp.prequal.backend.BackendResponse;
import dev.applymcp.prequal.backend.MockPrequalBackend;
import dev.applymcp.prequal.backend.PrequalApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrequalToolsTest {

    private static final String KEY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private PrequalTools tools;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);
        tools = new PrequalTools(new MockPrequalBackend(clock), clock);
    }

    @Test
    void fullHappyPath() {
        ToolResults.OffersResult offers = offers("Doe", "45402", 85_000, true);
        assertThat(offers.decision()).isEqualTo("QUALIFIED");
        assertThat(offers.offers()).hasSize(2);
        assertThat(offers.nextStep()).contains("prequal_accept_offer");

        ToolResults.AcceptResult accepted =
                tools.acceptOffer(offers.prequalId(), offers.offers().get(0).offerId(), KEY, true);
        assertThat(accepted.outcome()).isEqualTo("ACCEPTED");
        assertThat(accepted.applicationId()).startsWith("APP-");

        ToolResults.StatusResult status = tools.getApplicationStatus(accepted.applicationId());
        assertThat(status.status()).isEqualTo("APPROVED");
    }

    @Test
    void resultsNeverEchoPii() {
        ToolResults.OffersResult offers = offers("Doe", "45402", 85_000, true);
        String serialized = offers.toString();
        assertThat(serialized).doesNotContain("6789", "1990-04-12", "85000", "100 Main St");
    }

    @Test
    void consentIsEnforcedInCode() {
        assertThatThrownBy(() -> offers("Doe", "45402", 85_000, false))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("soft credit inquiry");
    }

    @Test
    void confirmationIsEnforcedInCode() {
        ToolResults.OffersResult offers = offers("Doe", "45402", 85_000, true);
        assertThatThrownBy(() -> tools.acceptOffer(offers.prequalId(), offers.offers().get(0).offerId(), KEY, false))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("not confirmed");
    }

    @Test
    void idempotencyKeyMustBeUuid() {
        ToolResults.OffersResult offers = offers("Doe", "45402", 85_000, true);
        assertThatThrownBy(() -> tools.acceptOffer(offers.prequalId(), offers.offers().get(0).offerId(), "abc", true))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void retryWithSameKeyReportsAlreadyAccepted() {
        ToolResults.OffersResult offers = offers("Doe", "45402", 85_000, true);
        String offerId = offers.offers().get(0).offerId();
        tools.acceptOffer(offers.prequalId(), offerId, KEY, true);

        ToolResults.AcceptResult retry = tools.acceptOffer(offers.prequalId(), offerId, KEY, true);
        assertThat(retry.outcome()).isEqualTo("ALREADY_ACCEPTED");
        assertThat(retry.nextStep()).contains("Do not call prequal_accept_offer again");
    }

    @Test
    void secondAcceptanceWithNewKeyIsRejected() {
        ToolResults.OffersResult offers = offers("Doe", "45402", 85_000, true);
        tools.acceptOffer(offers.prequalId(), offers.offers().get(0).offerId(), KEY, true);

        assertThatThrownBy(() -> tools.acceptOffer(offers.prequalId(), offers.offers().get(1).offerId(),
                "1b4e28ba-2fa1-11d2-883f-0016d3cca427", true))
                .isInstanceOf(ToolCallException.class)
                .hasMessageContaining("already accepted");
    }

    @Test
    void invalidInputsGetActionableMessages() {
        assertThatThrownBy(() -> tools.getOffers("Jane", "Doe", "12/04/1990", "6789", "100 Main St", "Dayton",
                "OH", "45402", 85_000, true)).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> tools.getOffers("Jane", "Doe", "1990-04-12", "123456789", "100 Main St", "Dayton",
                "OH", "45402", 85_000, true)).hasMessageContaining("4 digits");
        assertThatThrownBy(() -> tools.getOffers("Jane", "Doe", "2015-01-01", "6789", "100 Main St", "Dayton",
                "OH", "45402", 85_000, true)).hasMessageContaining("18");
        assertThatThrownBy(() -> tools.getOffers("Jane", "Doe", "1990-04-12", "6789", "100 Main St", "Dayton",
                "Ohio", "45402", 85_000, true)).hasMessageContaining("two-letter");
    }

    @Test
    void mapperTranslatesInProgressAndServerErrors() {
        BackendResponse<PrequalApi.AcceptResponse> inProgress =
                BackendResponse.ok(202, new PrequalApi.AcceptResponse("APP-1", "P"));
        assertThat(PrequalMapper.toAcceptResult(inProgress).outcome()).isEqualTo("IN_PROGRESS");

        BackendResponse<PrequalApi.AcceptResponse> timeout = BackendResponse.error(504, "BACKEND_TIMEOUT", "x");
        assertThatThrownBy(() -> PrequalMapper.toAcceptResult(timeout))
                .hasMessageContaining("SAME idempotency key");
    }

    @Test
    void codeTablesCoverKnownValues() {
        assertThat(PrequalMapper.decision("A1")).isEqualTo("QUALIFIED");
        assertThat(PrequalMapper.decision("D1")).isEqualTo("NOT_QUALIFIED");
        assertThat(PrequalMapper.decision("R1")).isEqualTo("NEEDS_MORE_INFO");
        assertThat(PrequalMapper.decision("ZZ")).isEqualTo("UNKNOWN");
        assertThat(PrequalMapper.applicationStatus("V")).isEqualTo("NEEDS_VERIFICATION");
    }

    private ToolResults.OffersResult offers(String lastName, String zip, int income, boolean consent) {
        return tools.getOffers("Jane", lastName, "1990-04-12", "6789", "100 Main St", "Dayton", "OH", zip,
                income, consent);
    }
}
