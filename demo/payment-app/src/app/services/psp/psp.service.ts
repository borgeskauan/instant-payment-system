import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {map, Observable} from 'rxjs';
import {PaymentSummary, PixKeySearchResult, TransferRequest, TransferResult} from './psp.model';
import {UserService} from '../user/user.service';
import {AppConfigService} from '../config/app-config.service';

interface TransferPreviewResponse {
  receiver: {
    name: string;
    taxId: string;
    account: {
      id: {
        bankCode: string;
      };
    };
  };
}

@Injectable({
  providedIn: 'root'
})
export class PspService {

  constructor(
    private readonly http: HttpClient,
    private readonly userService: UserService,
    private readonly config: AppConfigService,
  ) {
  }

  requestTransfer(request: TransferRequest) {
    const currentCustomerId = this.userService.requireUser().id;

    return this.http.post<TransferResult>(`${this.config.baseUrl}/transfer/execute`, {
      senderCustomerId: currentCustomerId,
      receiverPixKey: request.receiverPixKey,
      amount: request.amount,
      description: request.description,
    });
  }

  listPayments() {
    const customerId = this.userService.requireUser().id;
    return this.http.get<PaymentSummary[]>(`${this.config.baseUrl}/customers/${customerId}/payments`);
  }

  searchPixKey(pixKey: string): Observable<PixKeySearchResult> {
    return this.http.post<TransferPreviewResponse>(
      `${this.config.baseUrl}/transfer/preview`,
      {receiverPixKey: pixKey},
    ).pipe(
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
