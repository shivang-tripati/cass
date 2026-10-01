"use client";

import type { ReactNode } from "react";
import { AlertTriangleIcon, InfoIcon, LockIcon, RefreshCwIcon } from "lucide-react";

import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { toApiError, type ApiError } from "@/lib/api/error";

/**
 * Shared query states. F1 replaces nine hand-copied error blocks with one
 * component, because each copy had drifted: some showed `requestId` for 403,
 * some did not; some said "No permission", others "You don't have permission to
 * view …". A user hitting 403 on Campaigns and then on TTS saw two different
 * messages for the same condition.
 *
 * The distinctions encoded here are the ones the backend actually makes:
 *
 *   401 UNAUTHORIZED            the session is gone. Handled by the axios
 *                               interceptor's refresh + hard redirect, so this
 *                               component is not normally reached. Rendered
 *                               defensively if it is.
 *   403 FORBIDDEN               authenticated, but refused on capability or
 *                               tenant scope. NEVER a sign-in redirect — the
 *                               user is signed in, they just may not do this.
 *   404 RESOURCE_NOT_FOUND      not found, or outside the caller's boundary.
 *                               The backend reports a foreign resource exactly
 *                               like a nonexistent one, so this message must
 *                               not claim the object does not exist.
 *   409 CONFLICT                a state conflict (duplicate, illegal
 *                               transition). Worth naming the conflict.
 *   400/422 validation          `fieldErrors`; a form maps them to fields, a
 *                               list view just states the summary.
 *   429 / 5xx / transport       safe, generic wording. The backend's own
 *                               catch-all already replaces detail with
 *                               "An unexpected error occurred", and
 *                               `toApiError` falls back to a generic message,
 *                               so no stack trace or internal detail can reach
 *                               the user through this path.
 *
 * `requestId` is surfaced for 5xx only: it is a backend correlation handle
 * worth quoting in a support conversation, and it identifies no resource.
 */
export interface QueryStateProps {
  error: unknown;
  /** What is being loaded, e.g. "campaigns". Used in the 404 wording. */
  entityLabel?: string;
  /** Re-runs the query. Omit to render without a Retry affordance. */
  onRetry?: () => void;
  /** Overrides the whole message. */
  children?: ReactNode;
}

export function QueryErrorState({
  error,
  entityLabel,
  onRetry,
  children,
}: QueryStateProps) {
  if (children) return <>{children}</>;
  const apiError = toApiError(error);
  return <QueryErrorFromApiError apiError={apiError} entityLabel={entityLabel} onRetry={onRetry} />;
}

export function QueryErrorFromApiError({
  apiError,
  entityLabel,
  onRetry,
}: {
  apiError: ApiError;
  entityLabel?: string;
  onRetry?: () => void;
}) {
  const { title, description, icon: Icon, destructive } = describe(apiError, entityLabel);

  return (
    <Alert variant={destructive ? "destructive" : "default"}>
      <Icon aria-hidden="true" />
      <AlertTitle>{title}</AlertTitle>
      {description ? <AlertDescription>{description}</AlertDescription> : null}
      {onRetry ? (
        <AlertAction>
          <Button variant="outline" size="sm" onClick={onRetry}>
            <RefreshCwIcon aria-hidden="true" />
            Retry
          </Button>
        </AlertAction>
      ) : null}
    </Alert>
  );
}

function describe(apiError: ApiError, entityLabel?: string): {
  title: string;
  description: string | null;
  icon: typeof AlertTriangleIcon;
  destructive: boolean;
} {
  const subject = entityLabel ? ` ${entityLabel}` : "this resource";

  if (apiError.isNetworkError) {
    return {
      title: "Cannot reach the server",
      description: "Check your connection and try again.",
      icon: AlertTriangleIcon,
      destructive: true,
    };
  }

  switch (apiError.status) {
    case 401:
      return {
        title: "Your session has ended",
        description: "Sign in again to continue.",
        icon: LockIcon,
        destructive: false,
      };
    case 403:
      return {
        title: "You don't have access to this",
        description: `You are signed in, but your account is not permitted to view${subject}. Ask an administrator for access.`,
        icon: LockIcon,
        destructive: false,
      };
    case 404:
      return {
        title: `Not found`,
        description: `${cap(subject)} does not exist, or it is outside your organization.`,
        icon: InfoIcon,
        destructive: false,
      };
    case 409:
      return {
        title: "Conflict",
        description: apiError.message,
        icon: AlertTriangleIcon,
        destructive: true,
      };
    case 429:
      return {
        title: "Too many attempts",
        description: "Wait a moment and try again.",
        icon: AlertTriangleIcon,
        destructive: true,
      };
    default:
      break;
  }

  if (apiError.status >= 500) {
    return {
      title: "Something went wrong",
      description: apiError.requestId
        ? `The server could not complete this request. Request ID: ${apiError.requestId}`
        : "The server could not complete this request.",
      icon: AlertTriangleIcon,
      destructive: true,
    };
  }

  // 400 / 422 / anything else: the backend's own message is the safest text.
  return {
    title: "Request rejected",
    description: apiError.message,
    icon: AlertTriangleIcon,
    destructive: true,
  };
}

function cap(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Empty state for a list that loaded successfully with no rows.
 *
 * Distinguishes "nothing exists yet" from "your filters matched nothing", which
 * matters because only the second has a fix available to the user. */
export function EmptyState({
  title,
  description,
  icon: Icon = InfoIcon,
  action,
}: {
  title: string;
  description?: string;
  icon?: typeof InfoIcon;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center">
      <Icon aria-hidden="true" className="size-8 text-muted-foreground" />
      <p className="text-sm font-medium">{title}</p>
      {description ? (
        <p className="max-w-sm text-sm text-muted-foreground">{description}</p>
      ) : null}
      {action}
    </div>
  );
}
