import { Injectable } from '@angular/core';
import {HttpClient} from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class PspClientService {
  private baseUrl = 'https://api.example.com';

  constructor(private http: HttpClient) { }

}
