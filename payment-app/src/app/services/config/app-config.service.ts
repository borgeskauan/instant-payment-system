import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {
  private _baseUrl: string = 'http://localhost:8080';

  get baseUrl(): string {
    return this._baseUrl;
  }

  setBaseUrl(url: string): void {
    this._baseUrl = url;
  }
}
