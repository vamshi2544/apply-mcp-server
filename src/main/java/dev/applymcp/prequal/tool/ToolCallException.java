package dev.applymcp.prequal.tool;

/**
 * Thrown when a tool call cannot be completed. The message is returned to the agent as an
 * error result (isError=true), so it must say what went wrong and what to do next, and must
 * never contain applicant data or internal system details.
 */
public class ToolCallException extends RuntimeException {

    public ToolCallException(String message) {
        super(message);
    }
}
