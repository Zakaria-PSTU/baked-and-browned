import { isDevMode } from '@angular/core';
import { Routes } from '@angular/router';
import { requireAdmin, requireSignedIn, requireSignedOut } from './core/auth/auth.guards';

/** Feature routes are lazy; guards are UX only (the API enforces access). */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Baked & Browned',
    loadComponent: () => import('./features/catalog/catalog-page').then((m) => m.CatalogPage),
  },
  {
    path: 'products/:slug',
    title: 'Product · Baked & Browned',
    loadComponent: () => import('./features/catalog/product-page').then((m) => m.ProductPage),
  },
  {
    path: 'cart',
    title: 'Your cart · Baked & Browned',
    loadComponent: () => import('./features/cart/cart-page').then((m) => m.CartPage),
  },
  {
    path: 'checkout',
    title: 'Checkout · Baked & Browned',
    canMatch: [requireSignedIn],
    loadComponent: () => import('./features/checkout/checkout-page').then((m) => m.CheckoutPage),
  },
  {
    path: 'orders',
    pathMatch: 'full',
    title: 'My orders · Baked & Browned',
    canMatch: [requireSignedIn],
    loadComponent: () => import('./features/orders/my-orders-page').then((m) => m.MyOrdersPage),
  },
  {
    // The API's payment return endpoint forwards here (app.payments.online.return-url).
    path: 'orders/:orderId/payment',
    title: 'Checking your payment · Baked & Browned',
    canMatch: [requireSignedIn],
    loadComponent: () =>
      import('./features/orders/payment-return-page').then((m) => m.PaymentReturnPage),
  },
  {
    path: 'orders/:orderId',
    title: 'Your order · Baked & Browned',
    canMatch: [requireSignedIn],
    loadComponent: () => import('./features/orders/order-page').then((m) => m.OrderPage),
  },
  {
    path: 'login',
    title: 'Log in · Baked & Browned',
    canMatch: [requireSignedOut],
    loadComponent: () => import('./features/auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'register',
    title: 'Create an account · Baked & Browned',
    canMatch: [requireSignedOut],
    loadComponent: () => import('./features/auth/register-page').then((m) => m.RegisterPage),
  },
  {
    path: 'account',
    title: 'Your account · Baked & Browned',
    canMatch: [requireSignedIn],
    loadComponent: () => import('./features/account/account-page').then((m) => m.AccountPage),
  },
  {
    path: 'admin/orders',
    pathMatch: 'full',
    title: 'Orders · Baked & Browned',
    canMatch: [requireAdmin],
    loadComponent: () =>
      import('./features/admin/admin-orders-page').then((m) => m.AdminOrdersPage),
  },
  {
    path: 'admin/orders/:orderId',
    title: 'Order · Baked & Browned',
    canMatch: [requireAdmin],
    loadComponent: () => import('./features/admin/admin-order-page').then((m) => m.AdminOrderPage),
  },
  {
    path: 'admin/payments',
    title: 'Payments to verify · Baked & Browned',
    canMatch: [requireAdmin],
    loadComponent: () =>
      import('./features/admin/admin-payments-page').then((m) => m.AdminPaymentsPage),
  },
  {
    path: 'admin',
    title: 'Dashboard · Baked & Browned',
    canMatch: [requireAdmin],
    loadComponent: () =>
      import('./features/admin/admin-dashboard-page').then((m) => m.AdminDashboardPage),
  },
  {
    // The fake gateway's hosted page (app.payments.fake.checkout-base-url). Development builds
    // only; the API serves its endpoints only under the dev and test profiles.
    path: 'dev/fake-gateway',
    title: 'Test payment · Baked & Browned',
    canMatch: [() => isDevMode(), requireSignedIn],
    loadComponent: () => import('./features/dev/fake-gateway-page').then((m) => m.FakeGatewayPage),
  },
  {
    path: '**',
    title: 'Page not found · Baked & Browned',
    loadComponent: () => import('./features/not-found/not-found-page').then((m) => m.NotFoundPage),
  },
];
