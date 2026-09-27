"use client";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";

/**
 * Frontend capability constants mirrored from backend capability catalog.
 * These must match the backend capability keys exactly.
 */
export const Capability = {
  // User
  USER_VIEW: "USER_VIEW",
  USER_MANAGE: "USER_MANAGE",

  // Tenant
  TENANT_VIEW: "TENANT_VIEW",
  TENANT_MANAGE: "TENANT_MANAGE",

  // Reseller
  RESELLER_VIEW: "RESELLER_VIEW",
  RESELLER_MANAGE: "RESELLER_MANAGE",

  // DID
  DID_VIEW: "DID_VIEW",
  DID_MANAGE: "DID_MANAGE",

  // Campaign
  CAMPAIGN_VIEW: "CAMPAIGN_VIEW",
  CAMPAIGN_MANAGE: "CAMPAIGN_MANAGE",
  CAMPAIGN_EXECUTE: "CAMPAIGN_EXECUTE",

  // Contact
  CONTACT_VIEW: "CONTACT_VIEW",
  CONTACT_MANAGE: "CONTACT_MANAGE",

  // Audio
  AUDIO_VIEW: "AUDIO_VIEW",
  AUDIO_MANAGE: "AUDIO_MANAGE",
  AUDIO_APPROVE: "AUDIO_APPROVE",

  // TTS
  TTS_VIEW: "TTS_VIEW",
  TTS_MANAGE: "TTS_MANAGE",
  TTS_APPROVE: "TTS_APPROVE",
} as const;

export type Capability = (typeof Capability)[keyof typeof Capability];

/**
 * Checks if the user has a specific capability.
 */
export function hasCapability(user: AuthenticatedUserResponse | null | undefined, capability: Capability): boolean {
  if (!user || !user.capabilities) return false;
  return user.capabilities.includes(capability);
}

/**
 * Checks if the user has any of the specified capabilities.
 */
export function hasAnyCapability(user: AuthenticatedUserResponse | null | undefined, capabilities: Capability[]): boolean {
  if (!user || !user.capabilities) return false;
  return capabilities.some((cap) => user.capabilities!.includes(cap));
}

/**
 * Checks if the user has all of the specified capabilities.
 */
export function hasAllCapabilities(user: AuthenticatedUserResponse | null | undefined, capabilities: Capability[]): boolean {
  if (!user || !user.capabilities) return false;
  return capabilities.every((cap) => user.capabilities!.includes(cap));
}

/**
 * Checks if the user has platform-level access (SUPER_ADMIN equivalent).
 */
export function hasPlatformAccess(user: AuthenticatedUserResponse | null | undefined): boolean {
  if (!user) return false;
  return user.homeType === null && hasCapability(user, Capability.TENANT_VIEW);
}

/**
 * Checks if the user has reseller-level access.
 */
export function hasResellerAccess(user: AuthenticatedUserResponse | null | undefined): boolean {
  if (!user) return false;
  return user.homeType === "RESELLER";
}

/**
 * Checks if the user has tenant-level access.
 */
export function hasTenantAccess(user: AuthenticatedUserResponse | null | undefined): boolean {
  if (!user) return false;
  return user.homeType === "TENANT";
}