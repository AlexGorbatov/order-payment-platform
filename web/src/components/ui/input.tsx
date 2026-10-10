import * as React from "react";
import { cn } from "@/lib/utils";

const field =
  "w-full rounded-lg border border-line bg-surface px-3 text-sm text-ink placeholder:text-muted/70 transition-colors focus-visible:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30 disabled:opacity-50";

export function Input({ className, ...props }: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(field, "h-10", className)} {...props} />;
}

export function Textarea({ className, ...props }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn(field, "min-h-24 py-2", className)} {...props} />;
}
