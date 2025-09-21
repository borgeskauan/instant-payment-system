import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {AppConfigService} from '../config/app-config.service';
import {map, switchMap, tap} from 'rxjs';
import {Router} from '@angular/router';

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
  pixKeys?: string[];
}

@Injectable({
  providedIn: 'root'
})
export class UserService {

  private loggedInUser: ExternalCustomer | null = null;
  private pixKeys: string[] = [];

  constructor(private http: HttpClient, private config: AppConfigService, private router: Router) {
  }

  getUser() {
    if (!this.loggedInUser) {
      this.router.navigate(['/login']).catch(error => console.log(error));
    }

    return {
      id: this.loggedInUser?.id || '',
      name: this.loggedInUser?.name || '',
      taxId: this.loggedInUser?.taxId || '',
      balance: this.loggedInUser?.bankAccount.balance || 0,
      pixKeys: this.pixKeys
    }
  }

  login(name: string, taxId: string) {
    return this.http.post<ExternalCustomer>(`${this.config.baseUrl}/customers`, {name, taxId}).pipe(
      switchMap((loginResponse) => {
        this.loggedInUser = loginResponse;

        return this.fetchPixKeys().pipe(
          map(() => loginResponse)
        );
      })
    );
  }

  createPixKey(pixKey: string) {
    if (!this.loggedInUser) {
      throw new Error('User not logged in');
    }

    return this.http.post(`${this.config.baseUrl}/customers/${this.loggedInUser?.id}/pix-keys`, {pixKey}).pipe(
      tap(() => this.pixKeys.push(pixKey))
    );
  }

  fetchPixKeys() {
    if (!this.loggedInUser) {
      throw new Error('User not logged in');
    }

    return this.http.get<{
      pixKey: string
    }[]>(`${this.config.baseUrl}/customers/${this.loggedInUser?.id}/pix-keys`).pipe(
      tap((keys) => this.pixKeys = keys.map(k => k.pixKey))
    );
  }

  logout() {
    this.loggedInUser = null;
    this.pixKeys = [];

    this.router.navigate(['/login']).catch(error => console.log(error));
  }
}
