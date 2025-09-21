import { Injectable } from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {TransferExecutionRequest, TransferPreviewDetails} from './psp.client-model';

@Injectable({
  providedIn: 'root'
})
export class PspClientService {
  private baseUrl = 'http://localhost:8080';

  constructor(private http: HttpClient) { }

  searchPixKey(pixKey: string) {
    return this.http.post<TransferPreviewDetails>(`${this.baseUrl}/transfer/preview`, { receiverPixKey: pixKey });
  }

  requestTransfer(request: TransferExecutionRequest) {
    return this.http.post(`${this.baseUrl}/transfer/execute`, request);
  }
}
