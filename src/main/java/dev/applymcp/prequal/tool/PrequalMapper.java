package dev.applymcp.prequal.tool;

import java.util.List;

import dev.applymcp.prequal.backend.BackendResponse;
import dev.applymcp.prequal.backend.PrequalApi;

/**
 * Translates the backend contract into agent-facing results.
 * This is where internal codes become plain language and where each outcome gets a next step.
 */
public final class PrequalMapper {

    static final String QUALIFIED = "QUALIFIED";
    static final String NOT_QUALIFIED = "NOT_QUALIFIED";
    static final String NEEDS_MORE_INFO = "NEEDS_MORE_INFO";

    private PrequalMapper() {
    }

    // ---------- offers ----------

    public static ToolResults.OffersResult toOffersResult(BackendResponse<PrequalApi.OffersResponse> response) {
        if (!response.isSuccess()) {
            throw backendError(response, true);
        }
        PrequalApi.OffersResponse body = response.body();
        String decision = decision(body.decisionCd());
        List<ToolResults.OfferView> offers = body.offers() == null ? List.of() : body.offers().stream()
                .map(o -> new ToolResults.OfferView(o.offerId(), o.productName(), o.creditLine(),
                        o.purchaseApr(), o.expiresAt()))
                .toList();

        return switch (decision) {
            case QUALIFIED -> new ToolResults.OffersResult(decision, body.prequalId(), offers,
                    "The applicant prequalified for " + offers.size() + " offer(s). This is not a final credit decision.",
                    "Present each offer's product name, credit limit and purchase APR to the applicant exactly as returned. "
                            + "If the applicant chooses one, confirm the choice with them, then call prequal_accept_offer "
                            + "with this prequalId and the chosen offerId.");
            case NOT_QUALIFIED -> new ToolResults.OffersResult(decision, body.prequalId(), List.of(),
                    "The applicant did not prequalify for any offer at this time.",
                    "Tell the applicant they did not prequalify. Do not call this tool again with changed details "
                            + "unless the applicant says their details were entered incorrectly.");
            case NEEDS_MORE_INFO -> new ToolResults.OffersResult(decision, body.prequalId(), List.of(),
                    "The applicant's identity or address could not be confirmed from the details provided.",
                    "Ask the applicant to check their name, date of birth, address and ZIP code, then call this tool again.");
            default -> new ToolResults.OffersResult(decision, body.prequalId(), List.of(),
                    "The prequalification returned an unrecognised result.",
                    "Tell the applicant the result could not be determined and suggest trying again later.");
        };
    }

    static String decision(String decisionCd) {
        if (decisionCd == null) {
            return "UNKNOWN";
        }
        return switch (decisionCd) {
            case "A1" -> QUALIFIED;
            case "D1" -> NOT_QUALIFIED;
            case "R1" -> NEEDS_MORE_INFO;
            default -> "UNKNOWN";
        };
    }

    // ---------- accept ----------

    public static ToolResults.AcceptResult toAcceptResult(BackendResponse<PrequalApi.AcceptResponse> response) {
        int status = response.status();
        if (status == 201) {
            PrequalApi.AcceptResponse body = response.body();
            return new ToolResults.AcceptResult("ACCEPTED", body.applicationId(), applicationStatus(body.statusCd()),
                    "The offer was accepted and a credit card application was created.",
                    "Tell the applicant their application id. Call prequal_get_application_status to report the current status.");
        }
        if (status == 200) {
            PrequalApi.AcceptResponse body = response.body();
            return new ToolResults.AcceptResult("ALREADY_ACCEPTED", body.applicationId(), applicationStatus(body.statusCd()),
                    "This acceptance was already processed earlier with the same idempotency key. No new application was created.",
                    "Do not call prequal_accept_offer again. Use prequal_get_application_status with this applicationId.");
        }
        if (status == 202) {
            PrequalApi.AcceptResponse body = response.body();
            return new ToolResults.AcceptResult("IN_PROGRESS", body == null ? null : body.applicationId(), "PENDING",
                    "The acceptance is still being processed.",
                    "Do not call prequal_accept_offer again with a new key. Wait, then call prequal_get_application_status "
                            + "or retry prequal_accept_offer with the same idempotency key.");
        }

        String code = response.errorCode() == null ? "" : response.errorCode();
        switch (code) {
            case "IDEMPOTENCY_KEY_REUSED" -> throw new ToolCallException(
                    "This idempotency key was already used to accept a different offer. "
                            + "Only generate a new key if the applicant has explicitly chosen a different offer.");
            case "ALREADY_ACCEPTED" -> throw new ToolCallException(
                    "An offer from this prequalification was already accepted, so a second application cannot be created. "
                            + "Ask the applicant for their application id, or check status with prequal_get_application_status. "
                            + "Detail: " + safeDetail(response));
            case "OFFER_EXPIRED" -> throw new ToolCallException(
                    "This offer has expired. Tell the applicant, and if they want to continue, call prequal_get_offers again for new offers.");
            case "OFFER_NOT_FOUND", "PREQUAL_NOT_FOUND" -> throw new ToolCallException(
                    "The prequalId or offerId was not recognised. Use the exact ids returned by prequal_get_offers. Do not invent ids.");
            default -> {
                if (status >= 500) {
                    throw new ToolCallException(
                            "The outcome of this acceptance is unknown because the service did not respond. "
                                    + "Retry prequal_accept_offer once with the SAME idempotency key, which cannot create a duplicate. "
                                    + "Never retry with a new key.");
                }
                throw backendError(response, false);
            }
        }
    }

    // ---------- status ----------

    public static ToolResults.StatusResult toStatusResult(BackendResponse<PrequalApi.StatusResponse> response) {
        if (response.status() == 404) {
            throw new ToolCallException(
                    "No application exists with that id. Use the applicationId returned by prequal_accept_offer.");
        }
        if (!response.isSuccess()) {
            throw backendError(response, true);
        }
        PrequalApi.StatusResponse body = response.body();
        String status = applicationStatus(body.statusCd());
        return switch (status) {
            case "APPROVED" -> new ToolResults.StatusResult(body.applicationId(), status,
                    "The application has been approved.", body.updatedAt(),
                    "Tell the applicant their application was approved. Card details will be sent by the issuer.");
            case "DECLINED" -> new ToolResults.StatusResult(body.applicationId(), status,
                    "The application was declined.", body.updatedAt(),
                    "Tell the applicant their application was declined and that a notice explaining the reasons will be sent to them.");
            case "NEEDS_VERIFICATION" -> new ToolResults.StatusResult(body.applicationId(), status,
                    "Additional identity verification is required before a decision.", body.updatedAt(),
                    "Tell the applicant they will be contacted to verify their identity. Do not ask them for documents in this chat.");
            case "PENDING" -> new ToolResults.StatusResult(body.applicationId(), status,
                    "The application is still under review.", body.updatedAt(),
                    "Tell the applicant the application is under review. Do not check again repeatedly; check only if the applicant asks later.");
            default -> new ToolResults.StatusResult(body.applicationId(), status,
                    "The status could not be interpreted.", body.updatedAt(),
                    "Tell the applicant the status is not available right now.");
        };
    }

    static String applicationStatus(String statusCd) {
        if (statusCd == null) {
            return "UNKNOWN";
        }
        return switch (statusCd) {
            case "P" -> "PENDING";
            case "A" -> "APPROVED";
            case "D" -> "DECLINED";
            case "V" -> "NEEDS_VERIFICATION";
            default -> "UNKNOWN";
        };
    }

    // ---------- shared ----------

    private static ToolCallException backendError(BackendResponse<?> response, boolean safeToRetry) {
        int status = response.status();
        if (status >= 500) {
            return new ToolCallException(safeToRetry
                    ? "The prequalification service is temporarily unavailable. It is safe to retry once; if it fails again, tell the applicant to try later."
                    : "The prequalification service is temporarily unavailable. Tell the applicant to try later.");
        }
        if (status == 400) {
            return new ToolCallException("The request was rejected as invalid (" + response.errorCode()
                    + "). Check the inputs against the tool description and ask the applicant to correct them.");
        }
        return new ToolCallException("The request could not be completed (" + response.errorCode() + ").");
    }

    private static String safeDetail(BackendResponse<?> response) {
        return response.error() == null || response.error().message() == null ? "" : response.error().message();
    }
}
