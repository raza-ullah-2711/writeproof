import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AdminService } from './admin/admin.service';
import { HealthService } from './health/health.service';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App implements OnInit {
  protected readonly health = inject(HealthService);
  protected readonly admin = inject(AdminService);

  ngOnInit(): void {
    this.health.check();
  }
}
