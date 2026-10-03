import { Routes } from '@angular/router';
import { AdminAccount } from './admin/admin-account';
import { AdminAccounts } from './admin/admin-accounts';
import { AdminAudit } from './admin/admin-audit';
import { AdminDashboard } from './admin/admin-dashboard';
import { adminGuard } from './admin/admin.guard';
import { AdminShell } from './admin/admin-shell';
import { Calibration } from './calibration/calibration';
import { Capture } from './capture/capture';
import { ContactsPage } from './contacts-page/contacts-page';
import { Home } from './home/home';
import { LettersPage } from './letters-page/letters-page';
import { OpenLetterView } from './open-letter-view/open-letter-view';
import { OpenLettersPage } from './open-letters-page/open-letters-page';
import { Verify } from './verify/verify';

export const routes: Routes = [
  { path: '', component: Home, title: 'Writeproof' },
  { path: 'letters', component: LettersPage, title: 'Letters · Writeproof' },
  { path: 'contacts', component: ContactsPage, title: 'Contacts · Writeproof' },
  { path: 'open', component: OpenLettersPage, title: 'Open letters · Writeproof' },
  { path: 'open/:hash', component: OpenLetterView, title: 'Open letter · Writeproof' },
  { path: 'handwriting', component: Verify, title: 'Handwriting · Writeproof' },
  { path: 'capture', component: Capture, title: 'Capture playground · Writeproof' },
  {
    path: 'admin',
    component: AdminShell,
    canMatch: [adminGuard],
    children: [
      { path: '', component: AdminDashboard, title: 'Dashboard · Admin · Writeproof' },
      { path: 'accounts', component: AdminAccounts, title: 'Accounts · Admin · Writeproof' },
      { path: 'accounts/:id', component: AdminAccount, title: 'Account · Admin · Writeproof' },
      { path: 'audit', component: AdminAudit, title: 'Audit log · Admin · Writeproof' },
    ],
  },
  { path: 'calibration', component: Calibration, title: 'Help improve verification · Writeproof' },
];
