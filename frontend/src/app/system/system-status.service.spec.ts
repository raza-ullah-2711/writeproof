import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SystemStatusService } from './system-status.service';

describe('SystemStatusService', () => {
  it('reads as all-on until loaded, then follows the server, and keeps it on errors', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const system = TestBed.inject(SystemStatusService);
    const http = TestBed.inject(HttpTestingController);
    expect(system.status()).toMatchObject({ registrationOpen: true, sendingEnabled: true });

    const loading = system.refresh();
    http.expectOne('/api/system/status').flush({
      registrationOpen: false,
      sendingEnabled: true,
      openLettersEnabled: false,
      announcement: 'Paused for an upgrade',
    });
    await loading;
    expect(system.status()).toMatchObject({
      registrationOpen: false,
      announcement: 'Paused for an upgrade',
    });

    const failing = system.refresh();
    http.expectOne('/api/system/status').flush('', { status: 500, statusText: 'Error' });
    await failing;
    expect(system.status().registrationOpen).toBe(false);
  });
});
