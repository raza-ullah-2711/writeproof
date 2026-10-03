import { Routes } from '@angular/router';
import { adminGuard } from './admin.guard';
import { ADMIN_ROUTES } from './admin.routes';
import { AdminSignIn } from './admin-sign-in';

export const ADMIN_APP_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'admin' },
  { path: 'sign-in', component: AdminSignIn, title: 'Sign in · Admin · Writeproof' },
  { path: 'admin', canMatch: [adminGuard], children: ADMIN_ROUTES },
  { path: '**', redirectTo: 'sign-in' },
];
