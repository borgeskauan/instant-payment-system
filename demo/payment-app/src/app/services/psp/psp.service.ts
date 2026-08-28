import {Injectable} from '@angular/core';
import {map, Observable} from 'rxjs';
import {PixKeySearchResult, TransferRequest} from './psp.model';
import {PspClientService} from './client/psp-client.service';
import {UserService} from '../user/user.service';

@Injectable({
  providedIn: 'root'
})
export class PspService {

  constructor(private pspClient: PspClientService, private userService: UserService) {
  }

  requestTransfer(request: TransferRequest): Observable<any> {
    const currentCustomerId = this.userService.requireUser().id;

    return this.pspClient.requestTransfer({
      senderCustomerId: currentCustomerId,
      receiverPixKey: request.receiverPixKey,
      amount: request.amount,
      description: request.description,
    });
  }

  searchPixKey(pixKey: string): Observable<PixKeySearchResult> {
    return this.pspClient.searchPixKey(pixKey).pipe(
      map(response => ({
          name: response.receiver.name,
          taxId: response.receiver.taxId,
          institution: response.receiver.account.id.bankCode,
      }))
    );
  }
}
