"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useReducer } from "react";

const STORAGE_KEY = "opp.cart.v1";
export const MAX_QUANTITY = 10;
export const MAX_LINES = 20;

type Lines = Record<string, number>;
type Action =
  | { type: "set"; sku: string; quantity: number }
  | { type: "clear" };

function reducer(state: Lines, action: Action): Lines {
  switch (action.type) {
    case "clear":
      return {};
    case "set": {
      const next = { ...state };
      const quantity = Math.min(MAX_QUANTITY, Math.max(0, Math.floor(action.quantity)));
      if (quantity === 0) delete next[action.sku];
      else if (quantity > 0 && (next[action.sku] !== undefined || Object.keys(next).length < MAX_LINES)) next[action.sku] = quantity;
      return next;
    }
  }
}

interface CartContextValue {
  lines: Lines;
  count: number;
  quantityOf: (sku: string) => number;
  setQuantity: (sku: string, quantity: number) => void;
  clear: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

function loadLines(): Lines {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as Lines) : {};
  } catch {
    // storage blocked or corrupt: start with an empty cart
    return {};
  }
}

/** Rendered only after sign-in, that is, in the browser: reading the storage while initialising is safe. */
export function CartProvider({ children }: { children: React.ReactNode }) {
  const [lines, dispatch] = useReducer(reducer, undefined, loadLines);

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(lines));
    } catch {
      // the cart then lives only in memory
    }
  }, [lines]);

  const quantityOf = useCallback((sku: string) => lines[sku] ?? 0, [lines]);
  const setQuantity = useCallback((sku: string, quantity: number) => dispatch({ type: "set", sku, quantity }), []);
  const clear = useCallback(() => dispatch({ type: "clear" }), []);
  const value = useMemo<CartContextValue>(
    () => ({ lines, count: Object.values(lines).reduce((sum, q) => sum + q, 0), quantityOf, setQuantity, clear }),
    [lines, quantityOf, setQuantity, clear],
  );
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) throw new Error("useCart must be used inside CartProvider");
  return value;
}
