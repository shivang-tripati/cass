import type { Metadata } from "next";

import { ResellerSignupForm } from "@/components/auth/reseller-signup-form";

export const metadata: Metadata = {
  title: "Sign up · Reseller",
};

export default function ResellerSignupPage() {
  return <ResellerSignupForm />;
}
