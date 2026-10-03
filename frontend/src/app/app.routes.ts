import { Routes } from '@angular/router';
import { adminGuard } from './admin/admin.guard';
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
    canMatch: [adminGuard],
    // Loaded only for admins and moderators: everyone else never downloads the admin area.
    loadChildren: () => import('./admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },
  { path: 'calibration', component: Calibration, title: 'Help improve verification · Writeproof' },
];
