import {Injectable, signal} from '@angular/core';

export interface DemoPsp {
  id: string;
  name: string;
  bankCode: string;
  baseUrl: string;
}

export interface DemoRecipient {
  name: string;
  pixKey: string;
  provider: string;
}

export interface DemoAccount {
  id: string;
  name: string;
  taxId: string;
  providerId: string;
}

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {
  readonly providers: DemoPsp[] = [
    {id: 'psp-a', name: 'Bank A', bankCode: '11111111', baseUrl: '/api/psp-a'},
    {id: 'psp-b', name: 'Bank B', bankCode: '22222222', baseUrl: '/api/psp-b'},
  ];

  readonly demoRecipient: DemoRecipient = {
    name: 'Bob',
    pixKey: 'bob@example.com',
    provider: 'Bank B',
  };

  readonly demoAccounts: DemoAccount[] = [
    {id: 'alice', name: 'Alice', taxId: '11111111111', providerId: 'psp-a'},
    {id: 'bob', name: 'Bob', taxId: '22222222222', providerId: 'psp-b'},
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

  providerById(providerId: string): DemoPsp {
    const provider = this.providers.find(candidate => candidate.id === providerId);
    if (!provider) {
      throw new Error(`Unknown demo PSP: ${providerId}`);
    }
    return provider;
  }

  providerByBankCode(bankCode: string): DemoPsp | undefined {
    return this.providers.find(candidate => candidate.bankCode === bankCode);
  }
}
