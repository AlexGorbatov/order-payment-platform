"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/components/auth-provider";

/** Everyone lands on the part of the app their role is for. */
export default function Home() {
  const { user } = useAuth();
  const router = useRouter();
  useEffect(() => {
    router.replace(user.isAdmin ? "/admin" : user.isOps ? "/ops" : "/shop");
  }, [router, user]);
  return null;
}
