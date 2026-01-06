import { Injectable } from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {TransferExecutionRequest, TransferPreviewDetails} from './psp.client-model';
import {AppConfigService} from '../../config/app-config.service';
import {Observable} from 'rxjs';

export interface PspInfo {
  bankCode: string;
  name: string;
  status: string;
  version: string;
}

@Injectable({
  providedIn: 'root'
})
export class PspClientService {

  constructor(private http: HttpClient, private config: AppConfigService) { }

  getInfo(): Observable<PspInfo> {
    return this.http.get<PspInfo>(`${this.config.baseUrl}/info`);
  }

  searchPixKey(pixKey: string) {
    return this.http.post<TransferPreviewDetails>(`${this.config.baseUrl}/transfer/preview`, { receiverPixKey: pixKey });
  }

  requestTransfer(request: TransferExecutionRequest) {
    return this.http.post(`${this.config.baseUrl}/transfer/execute`, request);
  }
}
