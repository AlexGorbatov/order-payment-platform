"use client";

import { X } from "lucide-react";
import { Dialog as DialogPrimitive } from "radix-ui";
import * as React from "react";
import { cn } from "@/lib/utils";

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;

const overlay =
  "anim-fade fixed inset-0 z-50 bg-ink/40 backdrop-blur-[2px]";

export function DialogContent({
  className,
  children,
  title,
  description,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & { title: string; description?: string }) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className={overlay} />
      <DialogPrimitive.Content
        className={cn(
          "anim-pop fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-card border border-line bg-surface p-6 shadow-card",
          className,
        )}
        {...props}
      >
        <DialogPrimitive.Title className="font-display text-lg font-semibold">{title}</DialogPrimitive.Title>
        {description ? (
          <DialogPrimitive.Description className="mt-1.5 text-sm text-muted">{description}</DialogPrimitive.Description>
        ) : (
          <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>
        )}
        <div className="mt-5">{children}</div>
        <DialogPrimitive.Close
          className="absolute right-4 top-4 rounded-md p-1 text-muted hover:bg-surface-2 hover:text-ink"
          aria-label="Close"
        >
          <X className="size-4" />
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}

/** A panel that slides in from the right (the cart). */
export function SheetContent({
  className,
  children,
  title,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & { title: string }) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className={overlay} />
      <DialogPrimitive.Content
        className={cn(
          "anim-sheet fixed right-0 top-0 z-50 flex h-dvh w-full max-w-md flex-col border-l border-line bg-surface shadow-card",
          className,
        )}
        {...props}
      >
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <DialogPrimitive.Title className="font-display text-lg font-semibold">{title}</DialogPrimitive.Title>
          <DialogPrimitive.Close className="rounded-md p-1 text-muted hover:bg-surface-2 hover:text-ink" aria-label="Close">
            <X className="size-4" />
          </DialogPrimitive.Close>
        </div>
        <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
