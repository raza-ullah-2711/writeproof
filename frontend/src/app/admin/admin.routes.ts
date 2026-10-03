import { Routes } from '@angular/router';
import { AdminAccount } from './admin-account';
import { AdminAccounts } from './admin-accounts';
import { AdminAdmins } from './admin-admins';
import { AdminAudit } from './admin-audit';
import { AdminDashboard } from './admin-dashboard';
import { AdminModeration } from './admin-moderation';
import { adminOnlyGuard } from './admin-only.guard';
import { AdminShell } from './admin-shell';
import { AdminSystem } from './admin-system';

/** The admin area, lazy-loaded behind adminGuard (app.routes.ts). */
export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminShell,
    children: [
      {
        path: '',
        component: AdminDashboard,
        canActivate: [adminOnlyGuard],
        title: 'Dashboard · Admin · Writeproof',
      },
      {
        path: 'accounts',
        component: AdminAccounts,
        canActivate: [adminOnlyGuard],
        title: 'Accounts · Admin · Writeproof',
      },
      {
        path: 'accounts/:id',
        component: AdminAccount,
        canActivate: [adminOnlyGuard],
        title: 'Account · Admin · Writeproof',
      },
      { path: 'moderation', component: AdminModeration, title: 'Moderation · Admin · Writeproof' },
      {
        path: 'system',
        component: AdminSystem,
        canActivate: [adminOnlyGuard],
        title: 'System · Admin · Writeproof',
      },
      {
        path: 'admins',
        component: AdminAdmins,
        canActivate: [adminOnlyGuard],
        title: 'Admins · Admin · Writeproof',
      },
      {
        path: 'audit',
        component: AdminAudit,
        canActivate: [adminOnlyGuard],
        title: 'Audit log · Admin · Writeproof',
      },
    ],
  },
];
