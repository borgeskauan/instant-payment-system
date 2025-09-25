import {Injectable, computed, signal, Signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {AppConfigService} from '../config/app-config.service';
import {map, switchMap, tap} from 'rxjs';
import {Router} from '@angular/router';
import {BehaviorSubject} from 'rxjs';
import {toSignal} from '@angular/core/rxjs-interop';

interface ExternalCustomer {
  id: string;
  name: string;
  taxId: string;
  bankAccount: {
    balance: number;
  }
}

export interface User {
  id: string;
  name: string;
  taxId: string;
  balance: number;
  pixKeys: string[];
}

@Injectable({
  providedIn: 'root'
})
export class UserService {

  private loggedInUser = signal<ExternalCustomer | null>(null);
  private pixKeys$ = new BehaviorSubject<string[]>([]);
  private pixKeysSignal = toSignal(this.pixKeys$, {initialValue: []}); // Convert to signal

  private intervalId: number = 0;

  constructor(
    private http: HttpClient,
    private config: AppConfigService,
    private router: Router
  ) {
  }

  getUser(): Signal<User> {
    return computed(() => {
      const user = this.loggedInUser();
      if (!user) {
        this.navigateToLogin();
        throw new Error('User not logged in');
      }

      return {
        id: user.id,
        name: user.name,
        taxId: user.taxId,
        balance: user.bankAccount.balance,
        pixKeys: this.pixKeysSignal()
      };
    });
  }

  private startUserPooling() {
    if (this.intervalId) {
      return;
    }

    this.intervalId = window.setInterval(() => {
      const user = this.loggedInUser();
      if (!user) {
        return;
      }

      this.login(user.name, user.taxId).subscribe();
    }, 10000);
  }

  login(name: string, taxId: string) {
    return this.http.post<ExternalCustomer>(`${this.config.baseUrl}/customers`, {name, taxId}).pipe(
      switchMap((loginResponse) => {
        this.loggedInUser.set(loginResponse);
        this.startUserPooling();
        return this.fetchPixKeys().pipe(
          map(() => loginResponse)
        );
      })
    );
  }

  createPixKey(pixKey: string) {
    const user = this.loggedInUser();
    if (!user) {
      throw new Error('User not logged in');
    }
    return this.http.post(`${this.config.baseUrl}/customers/${user.id}/pix-keys`, {pixKey}).pipe(
      tap(() => {
        const updatedKeys = [...this.pixKeys$.getValue(), pixKey];
        this.pixKeys$.next(updatedKeys);
      })
    );
  }

  fetchPixKeys() {
    const user = this.loggedInUser();
    if (!user) {
      throw new Error('User not logged in');
    }
    return this.http.get<{ pixKey: string }[]>(`${this.config.baseUrl}/customers/${user.id}/pix-keys`).pipe(
      tap((keys) => this.pixKeys$.next(keys.map(k => k.pixKey)))
    );
  }

  logout() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = 0;
    }
    this.loggedInUser.set(null);
    this.pixKeys$.next([]);
    this.navigateToLogin();
  }

  private navigateToLogin() {
    this.router.navigate(['/login']).catch(error => console.log(error));
  }
}
