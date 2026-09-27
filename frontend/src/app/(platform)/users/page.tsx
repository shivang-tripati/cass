import type { Metadata } from "next";

import { UsersView } from "@/components/users/users-view";

// List state lives in the URL and is user-scoped; SSR per request keeps
// useSearchParams-based state handling simple and correct.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Users",
};

export default function UsersPage() {
  return <UsersView />;
}
