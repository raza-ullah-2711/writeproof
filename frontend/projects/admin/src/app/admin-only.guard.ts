import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AdminService } from './admin.service';

/** Moderators only have moderation: send them there from the admin-only pages. */
export const adminOnlyGuard: CanActivateFn = () =>
  inject(AdminService).role() === 'ADMIN' ? true : inject(Router).parseUrl('/admin/moderation');
