import {Component, OnDestroy} from '@angular/core';
import {DecimalPipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';
import {AppConfigService} from '../../services/config/app-config.service';
import {PspService} from '../../services/psp/psp.service';
import {UserService} from '../../services/user/user.service';

type TransferStep = 'pix' | 'amount' | 'confirm' | 'result';
type PaymentResult = 'checking' | 'settled' | 'rejected' | 'processing';

const OUTCOME_WAIT_MS = 1500;
const OUTCOME_POLL_INTERVAL_MS = 150;

@Component({
  selector: 'app-transfer',
  imports: [DecimalPipe, FormsModule],
  templateUrl: './transfer.html',
})
export class Transfer implements OnDestroy {
  step: TransferStep = 'pix';
  pixKey: string;
  amount: number | null = null;
  loading = false;
  errorMessage = '';
  recipient = {name: '', taxId: '', institution: '', bankCode: ''};
  paymentResult: PaymentResult = 'checking';
  formattedAmount = '';
  readonly customerName: string;
  readonly providerName: string;
  private paymentId = '';
  private paymentPollTimer?: number;
  private destroyed = false;

  get stepNumber(): number {
    return this.step === 'pix' ? 1 : this.step === 'amount' ? 2 : 3;
  }

  constructor(
    private readonly router: Router,
    private readonly pspService: PspService,
    private readonly config: AppConfigService,
    private readonly userService: UserService,
  ) {
    this.pixKey = this.config.provider().id === 'psp-a' ? this.config.demoRecipient.pixKey : '';
    this.providerName = this.config.provider().name;
    this.customerName = this.userService.user()?.name ?? '';
    if (!this.customerName) {
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
      next: transfer => {
        this.loading = false;
        this.paymentId = transfer.transferId;
        this.formattedAmount = new Intl.NumberFormat('pt-BR', {style: 'currency', currency: 'BRL'}).format(this.amount!);
        this.paymentResult = 'checking';
        this.step = 'result';
        this.pollPayment(performance.now() + OUTCOME_WAIT_MS);
      },
      error: () => {
        this.loading = false;
        this.errorMessage = 'Could not submit the payment.';
      },
    });
  }

  back(): void {
    this.errorMessage = '';
    if (this.step === 'result') {
      void this.router.navigate(['/home']);
      return;
    }
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

  viewRecipientAccount(): void {
    const recipientProvider = this.config.providerByBankCode(this.recipient.bankCode);
    if (!recipientProvider) {
      this.errorMessage = "Could not open the recipient's account.";
      return;
    }

    const currentProviderId = this.config.provider().id;
    this.loading = true;
    this.config.selectProvider(recipientProvider.id);
    this.userService.openCustomer(this.recipient.name, this.recipient.taxId).subscribe({
      next: () => void this.router.navigate(['/home']),
      error: () => {
        this.config.selectProvider(currentProviderId);
        this.loading = false;
        this.errorMessage = "Could not open the recipient's account.";
      },
    });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.paymentPollTimer !== undefined) {
      window.clearTimeout(this.paymentPollTimer);
    }
  }

  private pollPayment(deadline: number): void {
    this.pspService.listPayments().subscribe({
      next: payments => {
        const payment = payments.find(candidate => candidate.paymentId === this.paymentId);
        if (payment?.status === 'SETTLED') {
          this.paymentResult = 'settled';
          return;
        }
        if (payment?.status === 'REJECTED') {
          this.paymentResult = 'rejected';
          return;
        }
        this.scheduleNextPoll(deadline);
      },
      error: () => this.scheduleNextPoll(deadline),
    });
  }

  private scheduleNextPoll(deadline: number): void {
    if (this.destroyed) {
      return;
    }
    const remaining = deadline - performance.now();
    if (remaining <= 0) {
      this.paymentResult = 'processing';
      return;
    }
    this.paymentPollTimer = window.setTimeout(
      () => this.pollPayment(deadline),
      Math.min(OUTCOME_POLL_INTERVAL_MS, remaining),
    );
  }
}
