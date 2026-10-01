import type { FieldErrorDto, ProblemDetail } from "@/lib/api/types";

/**
 * Normalised API failure surfaced to UI code.
 *
 * Statuses below are NOT invented. VERIFIED against
 * `common/api/error/CommonErrorCode.java:7-16` and
 * `common/exception/GlobalExceptionHandler.java`:
 *
 *   400 BAD_REQUEST            malformed body, type mismatch, missing parameter,
 *                              and also 405 (the 405 branch sets title
 *                              "Method not allowed" but keeps code BAD_REQUEST)
 *   400 VALIDATION_ERROR       MethodArgumentNotValid / ConstraintViolation /
 *                              HandlerMethodValidation, always with `errors[]`
 *   401 UNAUTHORIZED           BusinessException, AuthenticationException, and
 *                              the bearer entry point
 *   403 FORBIDDEN              ForbiddenException (generic, non-leaking),
 *                              AccessDeniedException, access-denied handler
 *   404 RESOURCE_NOT_FOUND     ResourceNotFoundException, NoResourceFoundException
 *   409 CONFLICT               duplicate e164, duplicate attempt, illegal
 *                              lifecycle transition, already-approved asset
 *   422 BUSINESS_RULE_VIOLATION BusinessException with that code
 *   429 RATE_LIMITED           Redis login limiter
 *   500 INTERNAL_SERVER_ERROR  catch-all
 *   503 SERVICE_UNAVAILABLE    audio storage disabled (AudioStorageException)
 */

/** Synthetic code for a failure that never reached the backend.
 *
 * F1: this constant existed but was never assigned, so a network failure and an
 * opaque server error both produced `code === undefined` and the UI could not
 * tell them apart. It is now set on every transport-level failure. */
export const NETWORK_ERROR_CODE = "NETWORK_ERROR" as const;

/** Status used for a failure with no HTTP response at all (offline, timeout,
 * aborted request). Distinct from any real backend status. */
export const TRANSPORT_FAILURE_STATUS = 0;

const FALLBACK_MESSAGE = "Something went wrong. Please try again.";
const NETWORK_MESSAGE =
  "Cannot reach the server. Check your connection and try again.";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly requestId: string | undefined;
  readonly fieldErrors: readonly FieldErrorDto[];

  constructor(params: {
    status: number;
    message: string;
    code?: string;
    requestId?: string;
    fieldErrors?: readonly FieldErrorDto[];
  }) {
    super(params.message);
    this.name = "ApiError";
    this.status = params.status;
    this.code = params.code;
    this.requestId = params.requestId;
    this.fieldErrors = params.fieldErrors ?? [];
  }

  get isNetworkError(): boolean {
    return this.status === TRANSPORT_FAILURE_STATUS;
  }

  /** First backend message for a given field, if any. */
  fieldMessage(field: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === field)?.message;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/**
 * Converts any thrown value into an `ApiError`.
 *
 * Safe to call on a non-Axios error: a value with no recognisable HTTP response
 * becomes a transport failure rather than throwing.
 */
export function toApiError(error: unknown): ApiError {
  if (isApiError(error)) {
    return error;
  }

  const status = extractStatus(error);
  if (status === null) {
    // No HTTP response at all: network failure, timeout, aborted request.
    return new ApiError({
      status: TRANSPORT_FAILURE_STATUS,
      message: NETWORK_MESSAGE,
      code: NETWORK_ERROR_CODE,
    });
  }

  const problem = extractProblemDetail(error);
  if (!problem) {
    return new ApiError({ status, message: FALLBACK_MESSAGE });
  }

  return new ApiError({
    status,
    message: problem.detail ?? problem.title ?? FALLBACK_MESSAGE,
    code: problem.code,
    requestId: problem.requestId,
    fieldErrors: problem.errors ?? [],
  });
}

/* ---- Status predicates -----------------------------------------------------
 * Every one of these corresponds to a status the backend genuinely emits.
 * 401 and 403 are deliberately distinct: a 401 means "re-authenticate" and is
 * handled by the axios refresh interceptor, while a 403 means "you are
 * authenticated but not allowed" and must NOT redirect to sign-in.
 * ------------------------------------------------------------------------- */

/** No HTTP response: offline, DNS failure, timeout, abort. */
export function isTransportFailure(error: unknown): boolean {
  return toApiError(error).status === TRANSPORT_FAILURE_STATUS;
}

/** 401 — the session is missing, expired or revoked. Retry/refresh territory. */
export function isUnauthorized(error: unknown): boolean {
  return toApiError(error).status === 401;
}

/** 403 — authenticated but the backend refused on capability or tenant scope.
 * Must render a forbidden state, never a login redirect. */
export function isForbidden(error: unknown): boolean {
  return toApiError(error).status === 403;
}

/** 404 — not found, or outside the caller's boundary (the backend reports a
 * foreign resource exactly like a nonexistent one, by design). */
export function isNotFound(error: unknown): boolean {
  return toApiError(error).status === 404;
}

/** 409 — a state conflict: duplicate, illegal lifecycle transition, or an
 * operation that is meaningless in the resource's current state. */
export function isConflict(error: unknown): boolean {
  return toApiError(error).status === 409;
}

/** 400 with a VALIDATION_ERROR code, i.e. field-level errors the backend can
 * map back onto form inputs. */
export function isValidationError(error: unknown): boolean {
  const apiError = toApiError(error);
  return (
    apiError.status === 400 &&
    (apiError.code === "VALIDATION_ERROR" || apiError.fieldErrors.length > 0)
  );
}

/** 400 BAD_REQUEST without field errors: a malformed request the user cannot
 * fix by editing a field. */
export function isBadRequest(error: unknown): boolean {
  return toApiError(error).status === 400;
}

/** 429 — login throttling. */
export function isRateLimited(error: unknown): boolean {
  return toApiError(error).status === 429;
}

/** 5xx. The backend already replaces the detail with a generic message in its
 * catch-all, so nothing internal is exposed here either. */
export function isServerError(error: unknown): boolean {
  const status = toApiError(error).status;
  return status >= 500 && status <= 599;
}

/** True when the failure is worth retrying the same request for. 4xx other than
 * 408/429 will fail identically on a retry, so they are excluded. */
export function isRetryable(error: unknown): boolean {
  const status = toApiError(error).status;
  if (status === TRANSPORT_FAILURE_STATUS) return true;
  if (status === 429) return true;
  return status >= 500;
}

function extractStatus(error: unknown): number | null {
  if (
    typeof error === "object" &&
    error !== null &&
    "response" in error &&
    typeof (error as { response?: unknown }).response === "object" &&
    (error as { response?: { status?: unknown } }).response !== null
  ) {
    const status = (error as { response: { status?: unknown } }).response.status;
    return typeof status === "number" ? status : null;
  }
  return null;
}

function extractProblemDetail(error: unknown): ProblemDetail | undefined {
  const data = (
    error as { response?: { data?: unknown } } | undefined
  )?.response?.data;
  if (typeof data !== "object" || data === null || !("status" in data)) {
    return undefined;
  }
  return data as ProblemDetail;
}
