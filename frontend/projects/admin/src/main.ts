import { bootstrapApplication } from '@angular/platform-browser';
import { applyStoredTheme } from '@app/theme/theme.service';
import { AdminApp } from './app/admin-app';
import { adminAppConfig } from './app/admin-app.config';

applyStoredTheme();
bootstrapApplication(AdminApp, adminAppConfig).catch((err) => console.error(err));
