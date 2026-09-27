/**
 * API transport contracts mirrored 1:1 from the Spring Boot backend
 * (com.shivang.obd.common.api). Do not invent fields that the backend
 * does not send.
 */

/** com.shivang.obd.common.lifecycle.LifecycleStatus */
export type LifecycleStatus = "ACTIVE" | "SUSPENDED";

/** com.shivang.obd.common.api.error.CommonErrorCode */
export type ApiErrorCode =
  | "BAD_REQUEST"
  | "VALIDATION_ERROR"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "RESOURCE_NOT_FOUND"
  | "CONFLICT"
  | "BUSINESS_RULE_VIOLATION"
  | "RATE_LIMITED"
  | "INTERNAL_SERVER_ERROR"
  | "SERVICE_UNAVAILABLE";

/** com.shivang.obd.common.api.response.ResponseMetadata */
export interface ResponseMetadata {
  requestId?: string | null;
  timestamp?: string;
}

/** com.shivang.obd.common.api.response.PaginationMetadata */
export interface PaginationMetadata {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/**
 * com.shivang.obd.common.api.response.ApiResponse — the success envelope for
 * all endpoints except raw-204 deletes.
 */
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  message?: string | null;
  pagination?: PaginationMetadata | null;
  meta?: ResponseMetadata | null;
}

/** com.shivang.obd.common.api.error.FieldError */
export interface FieldErrorDto {
  field: string;
  code: string;
  message: string;
}

/**
 * Spring ProblemDetail (RFC 9457) extended by GlobalExceptionHandler with
 * `code`, `requestId`, `timestamp` and optional `errors`.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: ApiErrorCode;
  requestId?: string;
  timestamp?: string;
  errors?: FieldErrorDto[];
}
