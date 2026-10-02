import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { AdminService } from './admin.service';

/** Only admins and moderators reach the admin area; everyone else goes to the wallet page. */
export const adminGuard: CanMatchFn = async () => {
  const router = inject(Router);
  const admin = inject(AdminService);
  if (!inject(AuthService).authenticated()) {
    return router.parseUrl('/');
  }
  try {
    return (await admin.refresh()) ? true : router.parseUrl('/');
  } catch {
    return router.parseUrl('/');
  }
};
