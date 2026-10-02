import { Routes } from '@angular/router';
import { Capture } from './capture/capture';
import { Home } from './home/home';

export const routes: Routes = [
  { path: '', component: Home, title: 'Writeproof' },
  { path: 'capture', component: Capture, title: 'Handwriting capture · Writeproof' },
];
