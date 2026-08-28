import { Injectable } from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {PaymentSummary, TransferDetails, TransferExecutionRequest, TransferPreviewDetails} from './psp.client-model';
import {AppConfigService} from '../../config/app-config.service';

@Injectable({
  providedIn: 'root'
})
export class PspClientService {

  constructor(private http: HttpClient, private config: AppConfigService) { }

  searchPixKey(pixKey: string) {
    return this.http.post<TransferPreviewDetails>(`${this.config.baseUrl}/transfer/preview`, { receiverPixKey: pixKey });
  }

  requestTransfer(request: TransferExecutionRequest) {
    return this.http.post<TransferDetails>(`${this.config.baseUrl}/transfer/execute`, request);
  }

  listPayments(customerId: string) {
    return this.http.get<PaymentSummary[]>(`${this.config.baseUrl}/customers/${customerId}/payments`);
  }
}
