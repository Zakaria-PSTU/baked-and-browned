import type { OrderStatus } from '../../core/api/generated/models';

/** Customer-facing wording for each order status. */
export const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Waiting for payment',
  CONFIRMED: 'Confirmed',
  PREPARING: 'Being baked',
  READY: 'Ready',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

/** Orders that will not change any more (architecture, order state machine). */
export const FINISHED: ReadonlySet<OrderStatus> = new Set(['DELIVERED', 'CANCELLED', 'EXPIRED']);

export function statusLabel(status: OrderStatus): string {
  return STATUS_LABELS[status] ?? status;
}
