import {Injectable} from '@angular/core';
import {map, Observable} from 'rxjs';
import {PixKeySearchResult, TransferRequest} from './psp.model';
import {PspClientService} from './client/psp-client.service';
import {UserService} from '../user/user.service';
import {AppConfigService} from '../config/app-config.service';

@Injectable({
  providedIn: 'root'
})
export class PspService {

  constructor(
    private pspClient: PspClientService,
    private userService: UserService,
    private config: AppConfigService,
  ) {
  }

  requestTransfer(request: TransferRequest) {
    const currentCustomerId = this.userService.requireUser().id;

    return this.pspClient.requestTransfer({
      senderCustomerId: currentCustomerId,
      receiverPixKey: request.receiverPixKey,
      amount: request.amount,
      description: request.description,
    });
  }

  listPayments() {
    return this.pspClient.listPayments(this.userService.requireUser().id);
  }

  searchPixKey(pixKey: string): Observable<PixKeySearchResult> {
    return this.pspClient.searchPixKey(pixKey).pipe(
      map(response => {
        const bankCode = response.receiver.account.id.bankCode;
        return {
          name: response.receiver.name,
          taxId: response.receiver.taxId,
          institution: this.config.providerByBankCode(bankCode)?.name ?? 'Unknown bank',
          bankCode,
        };
      })
    );
  }
}
