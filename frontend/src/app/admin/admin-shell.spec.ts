import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AdminShell } from './admin-shell';
import { AdminRole, AdminService } from './admin.service';

describe('AdminShell', () => {
  const role = signal<AdminRole | null>('ADMIN');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminShell],
      providers: [provideRouter([]), { provide: AdminService, useValue: { role } }],
    }).compileComponents();
  });

  it('gives admins the dashboard and audit log, and states what admins cannot see', async () => {
    role.set('ADMIN');
    const fixture = TestBed.createComponent(AdminShell);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('.role')?.textContent).toBe('Administrator');
    expect([...el.querySelectorAll('.admin-nav a')].map((a) => a.textContent?.trim())).toEqual([
      'Dashboard',
      'Audit log',
    ]);
    expect(el.querySelector('.limits')?.textContent).toContain("can't be read here");
  });

  it('shows moderators no admin-only pages', async () => {
    role.set('MODERATOR');
    const fixture = TestBed.createComponent(AdminShell);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('.role')?.textContent).toBe('Moderator');
    expect(el.querySelector('.admin-nav')).toBeNull();
  });
});
