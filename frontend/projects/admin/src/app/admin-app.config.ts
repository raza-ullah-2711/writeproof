import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { adminAuthInterceptor } from './admin-auth.interceptor';
import { ADMIN_APP_ROUTES } from './admin-app.routes';

export const adminAppConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(ADMIN_APP_ROUTES),
    provideHttpClient(withFetch(), withInterceptors([adminAuthInterceptor])),
  ],
};
