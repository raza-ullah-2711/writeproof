import { bootstrapApplication } from '@angular/platform-browser';
import { applyStoredTheme } from './app/theme/theme.service';
import { appConfig } from './app/app.config';
import { App } from './app/app';

applyStoredTheme();
bootstrapApplication(App, appConfig).catch((err) => console.error(err));
