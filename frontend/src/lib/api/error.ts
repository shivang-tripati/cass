import type { FieldErrorDto, ProblemDetail } from "@/lib/api/types";

/** Synthetic code for failures that never reached the backend. */
export const NETWORK_ERROR_CODE = "NETWORK_ERROR" as const;

/**
 * Normalized API failure surfaced to UI code. Wraps the backend
 * ProblemDetail when one exists; represents network/transport failures
 * with a synthetic status of 0.
 */
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
    return this.status === 0;
  }

  /** First backend message for a given field, if any. */
  fieldMessage(field: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === field)?.message;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

const FALLBACK_MESSAGE = "Something went wrong. Please try again.";
const NETWORK_MESSAGE =
  "Cannot reach the server. Check your connection and try again.";

/** Parses an axios error payload into an ApiError; safe for unknown shapes. */
export function toApiError(error: unknown): ApiError {
  if (isApiError(error)) {
    return error;
  }

  const status = extractStatus(error);
  if (status === null) {
    // No HTTP response at all: network failure, timeout, aborted request.
    return new ApiError({ status: 0, message: NETWORK_MESSAGE });
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
