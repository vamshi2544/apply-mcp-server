package dev.applymcp.prequal.tool;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import dev.applymcp.prequal.backend.PrequalApi;
import dev.applymcp.prequal.backend.PrequalBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The three prequalification tools exposed over MCP.
 *
 * The descriptions are the real interface: they are the only thing the AI agent knows about
 * these capabilities. Every safety rule stated in a description is also enforced in code here,
 * because a description guides a well-behaved model but cannot stop a misled one.
 */
@Component
public class PrequalTools {

    private static final Logger log = LoggerFactory.getLogger(PrequalTools.class);

    private static final Pattern SSN_LAST4 = Pattern.compile("\\d{4}");
    private static final Pattern ZIP = Pattern.compile("\\d{5}");
    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final PrequalBackend backend;
    private final Clock clock;

    @Autowired
    public PrequalTools(PrequalBackend backend) {
        this(backend, Clock.systemUTC());
    }

    public PrequalTools(PrequalBackend backend, Clock clock) {
        this.backend = backend;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ prequal_get_offers

    @McpTool(
            name = "prequal_get_offers",
            title = "Check credit card prequalification",
            description = """
                    Check whether an applicant prequalifies for credit card offers, using a soft credit inquiry \
                    that does not affect the applicant's credit score.
                    Use this as the first step of any credit card prequalification.
                    Before calling: collect every input from the applicant themselves, and get their explicit agreement \
                    to a soft credit inquiry. Set consentSoftInquiry to true only if they agreed.
                    Returns a decision (QUALIFIED, NOT_QUALIFIED or NEEDS_MORE_INFO), a prequalId, and for QUALIFIED a \
                    list of offers. Follow the nextStep field in the result.
                    This is not an application and not a final credit decision. Safe to retry once on a timeout.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Check credit card prequalification",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false))
    public ToolResults.OffersResult getOffers(
            @McpToolParam(description = "Applicant's legal first name.", required = true) String firstName,
            @McpToolParam(description = "Applicant's legal last name.", required = true) String lastName,
            @McpToolParam(description = "Date of birth in YYYY-MM-DD format. Applicant must be 18 or older.", required = true)
            String dateOfBirth,
            @McpToolParam(description = "Last 4 digits of the applicant's Social Security number. Exactly 4 digits. Never ask for the full number.",
                    required = true) String ssnLast4,
            @McpToolParam(description = "Street address line, for example '100 Main St'.", required = true) String addressLine1,
            @McpToolParam(description = "City of residence.", required = true) String city,
            @McpToolParam(description = "Two-letter US state code, for example 'OH'.", required = true) String state,
            @McpToolParam(description = "Five-digit US ZIP code.", required = true) String zipCode,
            @McpToolParam(description = "Applicant's total annual income in US dollars, as a whole number.", required = true)
            Integer annualIncome,
            @McpToolParam(description = "True only if the applicant explicitly agreed to a soft credit inquiry in this conversation.",
                    required = true) Boolean consentSoftInquiry) {

        return traced("prequal_get_offers", () -> {
            if (!Boolean.TRUE.equals(consentSoftInquiry)) {
                throw new ToolCallException(
                        "The applicant has not agreed to a soft credit inquiry. Explain that checking prequalification uses a "
                                + "soft inquiry that does not affect their credit score, ask for their agreement, and call again only if they agree.");
            }
            LocalDate dob = validateInputs(firstName, lastName, dateOfBirth, ssnLast4, addressLine1, city, state,
                    zipCode, annualIncome);

            PrequalApi.OffersRequest request = new PrequalApi.OffersRequest(
                    new PrequalApi.Applicant(firstName.trim(), lastName.trim(), dob, ssnLast4,
                            new PrequalApi.Address(addressLine1.trim(), city.trim(), state, zipCode), annualIncome),
                    new PrequalApi.Consent(true, Instant.now(clock)));

            return PrequalMapper.toOffersResult(backend.getOffers(request, MDC.get("correlationId")));
        });
    }

    // ------------------------------------------------------------------ prequal_accept_offer

    @McpTool(
            name = "prequal_accept_offer",
            title = "Accept a prequalified credit card offer",
            description = """
                    Accept one prequalified credit card offer and create a credit card application. This cannot be undone.
                    Only call this after: (1) prequal_get_offers returned QUALIFIED, (2) the applicant has seen that offer's \
                    product name, credit limit and purchase APR, and (3) the applicant has explicitly said they want that \
                    specific offer. Never choose an offer on the applicant's behalf. Set applicantConfirmed to true only then.
                    Idempotency: generate one new UUID as idempotencyKey for each acceptance. If the call times out or \
                    fails with an unknown outcome, retry with the SAME idempotencyKey; this never creates a duplicate. \
                    Never retry with a new key. Only one offer per prequalId can be accepted.
                    Returns outcome ACCEPTED, ALREADY_ACCEPTED or IN_PROGRESS, plus an applicationId. Follow the nextStep field.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Accept a prequalified credit card offer",
                    readOnlyHint = false,
                    destructiveHint = true,
                    idempotentHint = true,
                    openWorldHint = false))
    public ToolResults.AcceptResult acceptOffer(
            @McpToolParam(description = "The prequalId returned by prequal_get_offers.", required = true) String prequalId,
            @McpToolParam(description = "The offerId the applicant chose, exactly as returned by prequal_get_offers.", required = true)
            String offerId,
            @McpToolParam(description = "A UUID you generate once for this acceptance and reuse unchanged on any retry.", required = true)
            String idempotencyKey,
            @McpToolParam(description = "True only if the applicant explicitly confirmed they want this specific offer.", required = true)
            Boolean applicantConfirmed) {

        return traced("prequal_accept_offer", () -> {
            if (!Boolean.TRUE.equals(applicantConfirmed)) {
                throw new ToolCallException(
                        "The applicant has not confirmed this offer. Show them the offer's product name, credit limit and purchase APR, "
                                + "ask them to confirm they want it, and call again only after they confirm.");
            }
            require(prequalId, "prequalId");
            require(offerId, "offerId");
            if (idempotencyKey == null || !UUID_PATTERN.matcher(idempotencyKey).matches()) {
                throw new ToolCallException("idempotencyKey must be a UUID, for example 7c9e6679-7425-40de-944b-e07fc1f90ae7. "
                        + "Generate one for this acceptance and reuse it on retries.");
            }

            PrequalApi.AcceptRequest request = new PrequalApi.AcceptRequest(prequalId.trim(), Instant.now(clock));
            // Audit point: in production, record who confirmed, when, and through which agent (without PII).
            return PrequalMapper.toAcceptResult(
                    backend.acceptOffer(offerId.trim(), request, idempotencyKey, MDC.get("correlationId")));
        });
    }

    // ------------------------------------------------------------------ prequal_get_application_status

    @McpTool(
            name = "prequal_get_application_status",
            title = "Get credit card application status",
            description = """
                    Get the current status of a credit card application created by prequal_accept_offer.
                    Use this when the applicant asks about their application, or once right after an acceptance.
                    Returns status PENDING, APPROVED, DECLINED or NEEDS_VERIFICATION with a plain-language meaning.
                    Read-only and always safe to call, but do not call it repeatedly in a loop while the status is PENDING.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Get credit card application status",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public ToolResults.StatusResult getApplicationStatus(
            @McpToolParam(description = "The applicationId returned by prequal_accept_offer, for example APP-55001.", required = true)
            String applicationId) {

        return traced("prequal_get_application_status", () -> {
            require(applicationId, "applicationId");
            return PrequalMapper.toStatusResult(backend.getStatus(applicationId.trim(), MDC.get("correlationId")));
        });
    }

    // ------------------------------------------------------------------ helpers

    private LocalDate validateInputs(String firstName, String lastName, String dateOfBirth, String ssnLast4,
                                     String addressLine1, String city, String state, String zipCode,
                                     Integer annualIncome) {
        require(firstName, "firstName");
        require(lastName, "lastName");
        require(addressLine1, "addressLine1");
        require(city, "city");

        LocalDate dob;
        try {
            dob = LocalDate.parse(dateOfBirth);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ToolCallException("dateOfBirth must be in YYYY-MM-DD format, for example 1990-04-12.");
        }
        if (Period.between(dob, LocalDate.now(clock)).getYears() < 18) {
            throw new ToolCallException("The applicant must be at least 18 years old to apply for a credit card.");
        }
        if (ssnLast4 == null || !SSN_LAST4.matcher(ssnLast4).matches()) {
            throw new ToolCallException("ssnLast4 must be exactly 4 digits. Ask only for the last 4 digits, never the full number.");
        }
        if (state == null || !STATE.matcher(state).matches()) {
            throw new ToolCallException("state must be a two-letter uppercase US state code, for example OH.");
        }
        if (zipCode == null || !ZIP.matcher(zipCode).matches()) {
            throw new ToolCallException("zipCode must be exactly 5 digits.");
        }
        if (annualIncome == null || annualIncome < 0) {
            throw new ToolCallException("annualIncome must be a whole number of US dollars, zero or more.");
        }
        return dob;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ToolCallException(name + " is required.");
        }
    }

    /**
     * Wraps every tool call with a correlation id and one log line.
     * Logs the tool name, outcome and duration only. Arguments are never logged because they contain PII.
     */
    private <T> T traced(String tool, Supplier<T> call) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            T result = call.get();
            outcome = "ok";
            return result;
        } catch (ToolCallException e) {
            outcome = "rejected";
            throw e;
        } finally {
            log.info("tool={} outcome={} correlationId={} durationMs={}",
                    tool, outcome, correlationId, (System.nanoTime() - start) / 1_000_000);
            MDC.remove("correlationId");
        }
    }
}
