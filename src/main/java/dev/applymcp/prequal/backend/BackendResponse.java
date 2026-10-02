package dev.applymcp.prequal.backend;

/**
 * Result of one backend call: the HTTP status plus either a body or an error.
 *
 * @param status HTTP status returned by the API (or synthesised by the mock)
 * @param body   response body on 2xx, otherwise null
 * @param error  error body on 4xx/5xx, otherwise null
 */
public record BackendResponse<T>(int status, T body, PrequalApi.ApiError error) {

    public static <T> BackendResponse<T> ok(int status, T body) {
        return new BackendResponse<>(status, body, null);
    }

    public static <T> BackendResponse<T> error(int status, String code, String message) {
        return new BackendResponse<>(status, null, new PrequalApi.ApiError(code, message));
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public String errorCode() {
        return error == null ? null : error.code();
    }
}
