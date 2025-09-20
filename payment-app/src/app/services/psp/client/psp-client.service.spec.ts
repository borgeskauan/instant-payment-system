import { TestBed } from '@angular/core/testing';

import { PspClientService } from './psp-client.service';

describe('PspClientService', () => {
  let service: PspClientService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(PspClientService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
