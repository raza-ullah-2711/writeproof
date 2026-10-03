import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AdminAuth } from './admin-auth.service';
import { AdminService } from './admin.service';

/** Only admins and moderators reach the admin area; everyone else goes to sign-in. */
export const adminGuard: CanMatchFn = async () => {
  const router = inject(Router);
  const admin = inject(AdminService);
  if (!inject(AdminAuth).authenticated()) {
    return router.parseUrl('/sign-in');
  }
  try {
    return (await admin.refresh()) ? true : router.parseUrl('/sign-in');
  } catch {
    return router.parseUrl('/sign-in');
  }
};
