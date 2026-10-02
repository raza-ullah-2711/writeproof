import { HttpTestingController, TestRequest } from '@angular/common/http/testing';

/** Waits for the next request to `url`; for flows that await between sequential calls. */
export function nextRequest(http: HttpTestingController, url: string): Promise<TestRequest> {
  return vi.waitFor(() => {
    const [req] = http.match(url);
    if (!req) {
      throw new Error(`No pending request to ${url}`);
    }
    return req;
  });
}
