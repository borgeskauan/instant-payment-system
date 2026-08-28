import {Component, inject} from '@angular/core';
import {Router} from '@angular/router';
import {AppConfigService, DemoAccount} from '../../services/config/app-config.service';
import {UserService} from '../../services/user/user.service';

@Component({
  selector: 'app-open-account',
  templateUrl: './open-account.html',
})
export class OpenAccount {
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private readonly config = inject(AppConfigService);

  readonly accounts = this.config.demoAccounts;
  readonly demoRecipient = this.config.demoRecipient;

  errorMessage = '';
  loadingAccountId = '';

  openAccount(account: DemoAccount): void {
    this.errorMessage = '';
    this.loadingAccountId = account.id;
    this.config.selectProvider(account.providerId);
    this.userService.openCustomer(account.name, account.taxId).subscribe({
      next: () => void this.router.navigate(['/home']),
      error: () => {
        this.loadingAccountId = '';
        this.errorMessage = 'Could not open the demo account.';
      },
    });
  }

  providerName(account: DemoAccount): string {
    return this.config.providerById(account.providerId).name;
  }

  providerBankCode(account: DemoAccount): string {
    return this.config.providerById(account.providerId).bankCode;
  }
}
