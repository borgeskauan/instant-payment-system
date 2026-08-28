import {Injectable, signal} from '@angular/core';

export interface DemoPsp {
  id: string;
  name: string;
  bankCode: string;
  baseUrl: string;
}

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {
  readonly providers: DemoPsp[] = [
    {id: 'psp-a', name: 'PSP A', bankCode: '11111111', baseUrl: 'http://localhost:8081'},
    {id: 'psp-b', name: 'PSP B', bankCode: '22222222', baseUrl: 'http://localhost:8082'},
  ];

  private readonly selectedProvider = signal(this.providers[0]);
  readonly provider = this.selectedProvider.asReadonly();

  get baseUrl(): string {
    return this.selectedProvider().baseUrl;
  }

  selectProvider(providerId: string): void {
    const provider = this.providers.find(candidate => candidate.id === providerId);
    if (!provider) {
      throw new Error(`Unknown demo PSP: ${providerId}`);
    }
    this.selectedProvider.set(provider);
  }
}
