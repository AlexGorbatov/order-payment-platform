import Link from "next/link";
import { LogoMark } from "@/components/logo";

export default function NotFound() {
  return (
    <div className="grid min-h-dvh place-items-center p-6 text-center">
      <div>
        <LogoMark className="mx-auto size-10" />
        <h1 className="mt-5 font-display text-3xl">There is nothing at this <span className="gradient-text">address</span></h1>
        <p className="mt-2 text-muted">The page may have moved, or the link is mistyped.</p>
        <Link href="/" className="mt-6 inline-block text-sm font-medium text-accent-fg hover:underline">
          Back to the start
        </Link>
      </div>
    </div>
  );
}
