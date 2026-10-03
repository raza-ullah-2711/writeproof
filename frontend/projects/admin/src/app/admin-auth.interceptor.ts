import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { AdminAuth } from './admin-auth.service';

/**
 * Attaches the admin-app token to same-origin API calls only. If the server rejects it (expired,
 * or the account was signed out), forget it, so the app asks to sign in again.
 */
export const adminAuthInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AdminAuth);
  const token = auth.token();
  if (!token || !req.url.startsWith('/api/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
    tap({
      error: (e: unknown) => {
        if (e instanceof HttpErrorResponse && e.status === 401 && auth.token() === token) {
          auth.logout();
        }
      },
    }),
  );
};
