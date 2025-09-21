import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class AppConfigService {
  readonly baseUrl: string;

  constructor() {
    const params = new URLSearchParams(window.location.search);
    this.baseUrl = params.get('apiUrl') || 'http://localhost:8080';
  }
}
