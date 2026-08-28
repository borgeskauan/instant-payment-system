import {Component, inject, OnDestroy, signal} from '@angular/core';
import {DatePipe, DecimalPipe} from '@angular/common';
import {Router} from '@angular/router';
import {AppConfigService} from '../../services/config/app-config.service';
import {UserService} from '../../services/user/user.service';
import {PspService} from '../../services/psp/psp.service';
import {PaymentSummary} from '../../services/psp/psp.model';

const ACCOUNT_REFRESH_INTERVAL_MS = 2000;

@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  imports: [DatePipe, DecimalPipe],
})
export class Home implements OnDestroy {
  private readonly router = inject(Router);
  private readonly userService = inject(UserService);
  private readonly config = inject(AppConfigService);
  private readonly pspService = inject(PspService);
  private refreshIntervalId?: number;

  readonly customer = this.userService.user;
  readonly demoRecipient = this.config.demoRecipient;
  readonly provider = this.config.provider;
  readonly payments = signal<PaymentSummary[]>([]);

  constructor() {
    if (!this.customer()) {
      void this.router.navigate(['/start']);
      return;
    }
    this.refreshAccount();
    this.refreshIntervalId = window.setInterval(() => this.refreshAccount(), ACCOUNT_REFRESH_INTERVAL_MS);
  }

  goToTransfer(): void {
    void this.router.navigate(['/transfer']);
  }

  goToCreatePixKey(): void {
    void this.router.navigate(['/create-pix-key']);
  }

  logout(): void {
    this.userService.logout();
  }

  ngOnDestroy(): void {
    if (this.refreshIntervalId !== undefined) {
      window.clearInterval(this.refreshIntervalId);
    }
  }

  getFirstLetter(name: string): string {
    return name ? name.charAt(0).toUpperCase() : 'U';
  }

  private refreshAccount(): void {
    this.userService.refreshCustomer().subscribe({error: () => undefined});
    this.pspService.listPayments().subscribe({
      next: payments => this.payments.set(payments.slice(0, 5)),
      error: () => undefined,
    });
  }
}
