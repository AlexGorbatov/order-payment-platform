"use client";

import { DropdownMenu as MenuPrimitive } from "radix-ui";
import * as React from "react";
import { cn } from "@/lib/utils";

export const DropdownMenu = MenuPrimitive.Root;
export const DropdownMenuTrigger = MenuPrimitive.Trigger;

export function DropdownMenuContent({ className, sideOffset = 6, ...props }: React.ComponentProps<typeof MenuPrimitive.Content>) {
  return (
    <MenuPrimitive.Portal>
      <MenuPrimitive.Content
        sideOffset={sideOffset}
        className={cn("z-50 min-w-48 rounded-lg border border-line bg-surface p-1 shadow-card", className)}
        {...props}
      />
    </MenuPrimitive.Portal>
  );
}

export function DropdownMenuItem({ className, ...props }: React.ComponentProps<typeof MenuPrimitive.Item>) {
  return (
    <MenuPrimitive.Item
      className={cn(
        "flex cursor-default select-none items-center gap-2 rounded-md px-2.5 py-2 text-sm outline-none data-[highlighted]:bg-surface-2 data-[disabled]:opacity-50 [&_svg]:size-4 [&_svg]:text-muted",
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuLabel({ className, ...props }: React.ComponentProps<typeof MenuPrimitive.Label>) {
  return <MenuPrimitive.Label className={cn("px-2.5 py-1.5 text-xs text-muted", className)} {...props} />;
}

export const DropdownMenuSeparator = (props: React.ComponentProps<typeof MenuPrimitive.Separator>) => (
  <MenuPrimitive.Separator className="my-1 h-px bg-line" {...props} />
);
