import {computed, Injectable, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Router} from '@angular/router';
import {map, Observable, switchMap, tap} from 'rxjs';
import {AppConfigService} from '../config/app-config.service';

const BALANCE_REFRESH_INTERVAL_MS = 2000;

interface CustomerSnapshot {
  customer: {
    id: string;
    name: string;
    taxId: string;
  };
  bankAccount: {
    account: {
      id: {
        bankCode: string;
      };
    };
    balance: number;
  };
}

export interface User {
  id: string;
  name: string;
  taxId: string;
  bankCode: string;
  balance: number;
  pixKeys: string[];
}

@Injectable({providedIn: 'root'})
export class UserService {

  private readonly customerSnapshot = signal<CustomerSnapshot | null>(null);
  private readonly pixKeys = signal<string[]>([]);
  private pollingId?: number;

  readonly user = computed<User | null>(() => {
    const snapshot = this.customerSnapshot();
    if (!snapshot) {
      return null;
    }
    return {
      id: snapshot.customer.id,
      name: snapshot.customer.name,
      taxId: snapshot.customer.taxId,
      bankCode: snapshot.bankAccount.account.id.bankCode,
      balance: snapshot.bankAccount.balance,
      pixKeys: this.pixKeys(),
    };
  });

  constructor(
    private readonly http: HttpClient,
    private readonly config: AppConfigService,
    private readonly router: Router,
  ) {
  }

  openCustomer(name: string, taxId: string): Observable<CustomerSnapshot> {
    return this.requestCustomer(name, taxId).pipe(
      tap(snapshot => {
        this.customerSnapshot.set(snapshot);
        this.startBalancePolling();
      }),
      switchMap(snapshot => this.fetchPixKeys().pipe(map(() => snapshot))),
    );
  }

  createPixKey(pixKey: string) {
    const user = this.requireUser();
    return this.http.post(`${this.config.baseUrl}/customers/${user.id}/pix-keys`, {pixKey}).pipe(
      tap(() => this.pixKeys.update(keys => [...keys, pixKey])),
    );
  }

  fetchPixKeys() {
    const user = this.requireUser();
    return this.http.get<{ pixKey: string }[]>(`${this.config.baseUrl}/customers/${user.id}/pix-keys`).pipe(
      tap(keys => this.pixKeys.set(keys.map(key => key.pixKey))),
    );
  }

  requireUser(): User {
    const user = this.user();
    if (!user) {
      throw new Error('No demo customer is open');
    }
    return user;
  }

  logout(): void {
    this.stopBalancePolling();
    this.customerSnapshot.set(null);
    this.pixKeys.set([]);
    void this.router.navigate(['/start']);
  }

  private requestCustomer(name: string, taxId: string): Observable<CustomerSnapshot> {
    return this.http.post<CustomerSnapshot>(`${this.config.baseUrl}/customers`, {name, taxId});
  }

  private startBalancePolling(): void {
    if (this.pollingId !== undefined) {
      return;
    }
    this.pollingId = window.setInterval(() => {
      const snapshot = this.customerSnapshot();
      if (!snapshot) {
        return;
      }
      this.requestCustomer(snapshot.customer.name, snapshot.customer.taxId).subscribe({
        next: refreshed => this.customerSnapshot.set(refreshed),
        error: () => undefined,
      });
    }, BALANCE_REFRESH_INTERVAL_MS);
  }

  private stopBalancePolling(): void {
    if (this.pollingId === undefined) {
      return;
    }
    window.clearInterval(this.pollingId);
    this.pollingId = undefined;
  }
}
