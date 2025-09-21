import { Injectable } from '@angular/core';
import {map, Observable, of} from 'rxjs';
import {PixKeySearchResult, TransferRequest} from './psp.model';
import {PspClientService} from './client/psp-client.service';

@Injectable({
  providedIn: 'root'
})
export class PspService {
  constructor(private pspClient: PspClientService) { }

  requestTransfer(request: TransferRequest): Observable<any> {
    return of(); // Placeholder for actual implementation
  }

  searchPixKey(pixKey: string): Observable<PixKeySearchResult> {
    return this.pspClient.searchPixKey(pixKey).pipe(
      map(response => {
        return {
          name: response.receiver.name,
          taxId: response.receiver.taxId,
          institution: response.receiver.account.id.bankCode,
        };
      })
    );
  }
}
