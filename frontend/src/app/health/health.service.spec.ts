import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HealthService } from './health.service';

describe('HealthService', () => {
  let service: HealthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(HealthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reports up when the backend health status is UP', () => {
    service.check();
    expect(service.status()).toBe('checking');

    http.expectOne('/actuator/health').flush({ status: 'UP' });

    expect(service.status()).toBe('up');
  });

  it('reports down when the backend health status is not UP', () => {
    service.check();
    http.expectOne('/actuator/health').flush({ status: 'DOWN' });

    expect(service.status()).toBe('down');
  });

  it('reports down when the request fails', () => {
    service.check();
    http
      .expectOne('/actuator/health')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });

    expect(service.status()).toBe('down');
  });
});
