import type { Metadata } from "next";

import { TenantSignupForm } from "@/components/auth/tenant-signup-form";

export const metadata: Metadata = {
  title: "Sign up · Tenant",
};

export default function TenantSignupPage() {
  return <TenantSignupForm />;
}
