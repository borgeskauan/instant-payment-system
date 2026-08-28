import {Component} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';
import {AppConfigService} from '../../services/config/app-config.service';
import {PspService} from '../../services/psp/psp.service';
import {UserService} from '../../services/user/user.service';

type TransferStep = 'pix' | 'amount' | 'confirm';

@Component({
  selector: 'app-transfer',
  imports: [FormsModule],
  templateUrl: './transfer.html',
})
export class Transfer {
  step: TransferStep = 'pix';
  pixKey: string;
  amount: number | null = null;
  loading = false;
  errorMessage = '';
  recipient = {name: '', taxId: '', institution: ''};

  constructor(
    private readonly router: Router,
    private readonly pspService: PspService,
    config: AppConfigService,
    userService: UserService,
  ) {
    this.pixKey = config.demoRecipient.pixKey;
    if (!userService.user()) {
      void this.router.navigate(['/start']);
    }
  }

  submitPixKey(): void {
    const pixKey = this.pixKey.trim();
    this.errorMessage = '';
    if (!pixKey) {
      this.errorMessage = 'Enter a PIX key.';
      return;
    }

    this.loading = true;
    this.pspService.searchPixKey(pixKey).subscribe({
      next: recipient => {
        this.pixKey = pixKey;
        this.recipient = recipient;
        this.step = 'amount';
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.errorMessage = 'PIX key not found.';
      },
    });
  }

  proceedToConfirm(): void {
    this.errorMessage = '';
    if (!this.amount || this.amount <= 0) {
      this.errorMessage = 'Enter a positive amount.';
      return;
    }
    this.step = 'confirm';
  }

  confirmTransfer(): void {
    if (!this.amount || this.amount <= 0) {
      this.errorMessage = 'Enter a positive amount.';
      return;
    }

    this.loading = true;
    this.errorMessage = '';
    this.pspService.requestTransfer({
      amount: this.amount,
      receiverPixKey: this.pixKey,
      description: 'Reference demo payment',
    }).subscribe({
      next: () => {
        const amount = new Intl.NumberFormat('pt-BR', {style: 'currency', currency: 'BRL'}).format(this.amount!);
        window.alert(`${amount} payment submitted to ${this.recipient.name}. The balance will update after the final outcome.`);
        void this.router.navigate(['/home']);
      },
      error: () => {
        this.loading = false;
        this.errorMessage = 'Could not submit the payment.';
      },
    });
  }

  back(): void {
    this.errorMessage = '';
    if (this.step === 'confirm') {
      this.step = 'amount';
      return;
    }
    if (this.step === 'amount') {
      this.step = 'pix';
      this.amount = null;
      return;
    }
    void this.router.navigate(['/home']);
  }
}
