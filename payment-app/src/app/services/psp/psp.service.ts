import {Injectable} from '@angular/core';
import {map, Observable, of, switchMap, timeout} from 'rxjs';
import {PixKeySearchResult, TransferRequest} from './psp.model';
import {PspClientService} from './client/psp-client.service';
import {UserService} from '../user/user.service';
import {TransferPreviewDetails} from './client/psp.client-model';

@Injectable({
  providedIn: 'root'
})
export class PspService {

  private pixKeyCache: { [key: string]: TransferPreviewDetails } = {};

  constructor(private pspClient: PspClientService, private userService: UserService) {
  }

  requestTransfer(request: TransferRequest): Observable<any> {
    const currentCustomerId = this.userService.getUser()().id;

    return this.pspClient.requestTransfer({
      senderCustomerId: currentCustomerId,
      receiver: this.pixKeyCache[request.receiverPixKey].receiver,
      amount: request.amount
    });
  }

  searchPixKey(pixKey: string): Observable<PixKeySearchResult> {
    return this.pspClient.searchPixKey(pixKey).pipe(
      map(response => {
        this.pixKeyCache[pixKey] = response;

        return {
          name: response.receiver.name,
          taxId: response.receiver.taxId,
          institution: response.receiver.account.id.bankCode,
        };
      })
    );
  }
}
