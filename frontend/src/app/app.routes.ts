import { Routes } from '@angular/router';
import { Home } from './home/home';

// Every page but the home page loads on first visit, so someone opening a shared letter downloads
// only what that page needs.
export const routes: Routes = [
  { path: '', component: Home, title: 'Writeproof' },
  {
    path: 'letters',
    loadComponent: () => import('./letters-page/letters-page').then((m) => m.LettersPage),
    title: 'Letters · Writeproof',
  },
  {
    path: 'archive',
    loadComponent: () => import('./archive-page/archive-page').then((m) => m.ArchivePage),
    title: 'Family archive · Writeproof',
  },
  {
    path: 'contacts',
    loadComponent: () => import('./contacts-page/contacts-page').then((m) => m.ContactsPage),
    title: 'Contacts · Writeproof',
  },
  {
    path: 'open',
    loadComponent: () =>
      import('./open-letters-page/open-letters-page').then((m) => m.OpenLettersPage),
    title: 'Open letters · Writeproof',
  },
  {
    path: 'open/:hash',
    loadComponent: () =>
      import('./open-letter-view/open-letter-view').then((m) => m.OpenLetterView),
    title: 'Open letter · Writeproof',
  },
  {
    path: 'handwriting',
    loadComponent: () => import('./verify/verify').then((m) => m.Verify),
    title: 'Handwriting · Writeproof',
  },
  {
    path: 'capture',
    loadComponent: () => import('./capture/capture').then((m) => m.Capture),
    title: 'Capture playground · Writeproof',
  },
  // The admin area is a separate app on its own origin (projects/admin, docs/admin.md); old links
  // to it here land on the home page.
  { path: 'admin', redirectTo: '' },
  {
    path: 'calibration',
    loadComponent: () => import('./calibration/calibration').then((m) => m.Calibration),
    title: 'Help improve verification · Writeproof',
  },
];
