import { describe, expect, it } from "vitest";

import {
  ApiError,
  NETWORK_ERROR_CODE,
  isBadRequest,
  isConflict,
  isForbidden,
  isNotFound,
  isRateLimited,
  isRetryable,
  isServerError,
  isTransportFailure,
  isUnauthorized,
  isValidationError,
  toApiError,
} from "@/lib/api/error";

/**
 * F1 — error normalization contract.
 *
 * Every status asserted here is one the backend actually emits
 * (`CommonErrorCode.java:7-16`). The point of these tests is §9 of the F1 brief:
 * the UI must be able to tell 401 from 403 from 404 from 409 from validation
 * from 429 from 5xx from a network failure, and 401 must never be treated as a
 * permission problem or vice versa.
 */

function axiosError(status: number, data?: unknown) {
  return { response: { status, data } };
}

const PROBLEM = {
  type: "about:blank",
  title: "Forbidden",
  status: 403,
  detail: "Forbidden.",
  code: "FORBIDDEN",
  requestId: "req-1",
  timestamp: "2026-09-29T00:00:00Z",
};

describe("toApiError", () => {
  it("maps a ProblemDetail body onto ApiError", () => {
    const error = toApiError(axiosError(403, PROBLEM));
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(403);
    expect(error.code).toBe("FORBIDDEN");
    expect(error.message).toBe("Forbidden.");
    expect(error.requestId).toBe("req-1");
  });

  it("prefers detail, then title, then a generic fallback", () => {
    expect(
      toApiError(axiosError(409, { status: 409, title: "Conflict", detail: "Already approved." })).message,
    ).toBe("Already approved.");
    expect(toApiError(axiosError(409, { status: 409, title: "Conflict" })).message).toBe("Conflict");
    expect(toApiError(axiosError(409, { status: 409 })).message).toBe(
      "Something went wrong. Please try again.",
    );
  });

  it("treats a response with no ProblemDetail shape as a status-only failure", () => {
    const error = toApiError(axiosError(500, "<html>oops</html>"));
    expect(error.status).toBe(500);
    expect(error.message).toBe("Something went wrong. Please try again.");
  });

  it("classifies a missing response as a transport failure and tags it", () => {
    // F0 had a NETWORK_ERROR_CODE constant that was never assigned, so a
    // network failure was indistinguishable from an opaque server error.
    const error = toApiError({ message: "Network Error" });
    expect(error.isNetworkError).toBe(true);
    expect(error.status).toBe(0);
    expect(error.code).toBe(NETWORK_ERROR_CODE);
    expect(error.message).toBe(
      "Cannot reach the server. Check your connection and try again.",
    );
  });

  it("is idempotent for an already-normalised error", () => {
    const first = toApiError(axiosError(403, PROBLEM));
    expect(toApiError(first)).toBe(first);
  });

  it("carries field errors for validation failures", () => {
    const error = toApiError(
      axiosError(400, {
        status: 400,
        code: "VALIDATION_ERROR",
        detail: "One or more fields are invalid.",
        errors: [{ field: "name", code: "NotBlank", message: "must not be blank" }],
      }),
    );
    expect(error.fieldErrors).toHaveLength(1);
    expect(error.fieldMessage("name")).toBe("must not be blank");
    expect(error.fieldMessage("missing")).toBeUndefined();
  });
});

describe("status predicates", () => {
  it("separates 401 from 403 — the distinction the auth flow depends on", () => {
    const unauth = axiosError(401, { status: 401, code: "UNAUTHORIZED" });
    const forbidden = axiosError(403, PROBLEM);

    expect(isUnauthorized(unauth)).toBe(true);
    expect(isForbidden(unauth)).toBe(false);

    expect(isForbidden(forbidden)).toBe(true);
    expect(isUnauthorized(forbidden)).toBe(false);
  });

  it("recognises 404, 409 and 429", () => {
    expect(isNotFound(axiosError(404, { status: 404 }))).toBe(true);
    expect(isConflict(axiosError(409, { status: 409 }))).toBe(true);
    expect(isRateLimited(axiosError(429, { status: 429, code: "RATE_LIMITED" }))).toBe(true);
  });

  it("identifies validation errors by code or by the presence of field errors", () => {
    expect(
      isValidationError(axiosError(400, { status: 400, code: "VALIDATION_ERROR" })),
    ).toBe(true);
    expect(
      isValidationError(
        axiosError(400, {
          status: 400,
          errors: [{ field: "slug", code: "Pattern", message: "bad" }],
        }),
      ),
    ).toBe(true);
    // A plain 400 with no field detail is a bad request, not a field error.
    expect(isValidationError(axiosError(400, { status: 400, code: "BAD_REQUEST" }))).toBe(false);
    expect(isBadRequest(axiosError(400, { status: 400, code: "BAD_REQUEST" }))).toBe(true);
  });

  it("recognises 5xx and transport failures", () => {
    expect(isServerError(axiosError(500, { status: 500 }))).toBe(true);
    expect(isServerError(axiosError(503, { status: 503 }))).toBe(true);
    expect(isServerError(axiosError(400, { status: 400 }))).toBe(false);
    expect(isTransportFailure({ message: "Network Error" })).toBe(true);
  });

  it("marks only transient failures as retryable", () => {
    // A 4xx will fail identically on a retry, so retrying it is wrong.
    expect(isRetryable({ message: "Network Error" })).toBe(true);
    expect(isRetryable(axiosError(500, { status: 500 }))).toBe(true);
    expect(isRetryable(axiosError(429, { status: 429 }))).toBe(true);
    expect(isRetryable(axiosError(403, PROBLEM))).toBe(false);
    expect(isRetryable(axiosError(409, { status: 409 }))).toBe(false);
  });

  it("handles the 405 that carries a BAD_REQUEST code", () => {
    // VERIFIED: GlobalExceptionHandler's 405 branch sets title "Method not
    // allowed" but leaves code = BAD_REQUEST.
    const error = toApiError(
      axiosError(405, { status: 405, code: "BAD_REQUEST", title: "Method not allowed" }),
    );
    expect(error.status).toBe(405);
    expect(error.message).toBe("Method not allowed");
  });
});
