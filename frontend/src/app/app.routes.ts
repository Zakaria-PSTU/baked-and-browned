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
    path: 'admin',
    title: 'Dashboard · Baked & Browned',
    canMatch: [requireAdmin],
    loadComponent: () =>
      import('./features/admin/admin-dashboard-page').then((m) => m.AdminDashboardPage),
  },
  {
    path: '**',
    title: 'Page not found · Baked & Browned',
    loadComponent: () => import('./features/not-found/not-found-page').then((m) => m.NotFoundPage),
  },
];
