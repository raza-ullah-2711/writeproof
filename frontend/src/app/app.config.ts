import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './auth/auth.interceptor';
import { PUBLIC_LOG, SIGSTORE_REKOR } from './letters/rekor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    // Production builds hold the ledger to its anchors in Sigstore's public log (docs/ledger.md).
    { provide: PUBLIC_LOG, useFactory: () => (isDevMode() ? null : SIGSTORE_REKOR) },
  ],
};
