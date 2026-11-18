import type { OrderStatus } from '../../core/api/generated/models';

/**
 * What an ADMIN may do from each status through `adminChangeOrderStatus` (architecture, order
 * state machine). Confirming a PENDING_PAYMENT order goes through payment verification instead,
 * and EXPIRED orders only through reconciliation, so neither has a next step here. The server
 * enforces all of this; the screen only offers what it would accept.
 */
export const NEXT_STEP: Partial<Record<OrderStatus, { status: OrderStatus; label: string }>> = {
  CONFIRMED: { status: 'PREPARING', label: 'Start baking' },
  PREPARING: { status: 'READY', label: 'Mark ready' },
  READY: { status: 'OUT_FOR_DELIVERY', label: 'Send out for delivery' },
  OUT_FOR_DELIVERY: { status: 'DELIVERED', label: 'Mark delivered' },
};

/** Statuses an ADMIN can cancel from (with a reason). */
export const CANCELLABLE: ReadonlySet<OrderStatus> = new Set([
  'PENDING_PAYMENT',
  'CONFIRMED',
  'PREPARING',
  'READY',
]);

/** Admin-facing names, more precise than the customer's wording. */
export const ADMIN_STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  CONFIRMED: 'Confirmed',
  PREPARING: 'Preparing',
  READY: 'Ready',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};
