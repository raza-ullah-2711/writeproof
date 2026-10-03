import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AccountStatusService } from './auth/account-status.service';
import { HealthService } from './health/health.service';
import { SystemStatusService } from './system/system-status.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App implements OnInit {
  protected readonly health = inject(HealthService);
  protected readonly status = inject(AccountStatusService);
  protected readonly system = inject(SystemStatusService);

  ngOnInit(): void {
    this.health.check();
    void this.system.refresh();
  }
}
