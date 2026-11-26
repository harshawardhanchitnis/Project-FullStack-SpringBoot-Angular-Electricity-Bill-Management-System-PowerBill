import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./service-status').then((component) => component.ServiceStatus),
  },
  {
    path: '**',
    loadComponent: () => import('./page-not-found').then((component) => component.PageNotFound),
  },
];
