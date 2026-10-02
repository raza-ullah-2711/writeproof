import { Routes } from '@angular/router';
import { Capture } from './capture/capture';
import { Home } from './home/home';
import { LettersPage } from './letters-page/letters-page';
import { Verify } from './verify/verify';

export const routes: Routes = [
  { path: '', component: Home, title: 'Writeproof' },
  { path: 'letters', component: LettersPage, title: 'Letters · Writeproof' },
  { path: 'handwriting', component: Verify, title: 'Handwriting · Writeproof' },
  { path: 'capture', component: Capture, title: 'Capture playground · Writeproof' },
];
