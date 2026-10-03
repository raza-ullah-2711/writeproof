import { Component, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { AdminAuth } from './admin-auth.service';

/** The admin app's root: its own origin, its own sign-in, nothing from the public app's pages. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    <main class="shell">
      <header>
        <h1>Writeproof <span>admin</span></h1>
        @if (auth.authenticated()) {
          <button type="button" (click)="signOut()">Sign out</button>
        }
      </header>
      <router-outlet />
    </main>
  `,
  styles: `
    .shell {
      max-width: 60rem;
      margin: 2rem auto;
      padding: 0 1rem;
      font-family: system-ui, sans-serif;
    }
    header {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
    }
    h1 {
      font-family: Georgia, 'Times New Roman', serif;
    }
    h1 span {
      font-family: system-ui, sans-serif;
      font-size: 0.875rem;
      font-weight: normal;
      color: #555;
    }
  `,
})
export class AdminApp {
  protected readonly auth = inject(AdminAuth);
  private readonly router = inject(Router);

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/sign-in');
  }
}
