package dev.applymcp.prequal.backend;

/**
 * Adapter boundary between the MCP tool layer and the prequal REST APIs.
 *
 * Two implementations exist:
 * - {@link MockPrequalBackend}: deterministic in-memory mock (prequal.backend=mock, the default)
 * - {@link HttpPrequalBackend}: real HTTP calls through the API gateway (prequal.backend=http)
 *
 * Switching between them is configuration only. The tool layer never changes.
 */
public interface PrequalBackend {

    BackendResponse<PrequalApi.OffersResponse> getOffers(PrequalApi.OffersRequest request, String correlationId);

    BackendResponse<PrequalApi.AcceptResponse> acceptOffer(String offerId, PrequalApi.AcceptRequest request,
                                                           String idempotencyKey, String correlationId);

    BackendResponse<PrequalApi.StatusResponse> getStatus(String applicationId, String correlationId);
}
