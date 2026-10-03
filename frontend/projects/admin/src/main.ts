import { bootstrapApplication } from '@angular/platform-browser';
import { AdminApp } from './app/admin-app';
import { adminAppConfig } from './app/admin-app.config';

bootstrapApplication(AdminApp, adminAppConfig).catch((err) => console.error(err));
