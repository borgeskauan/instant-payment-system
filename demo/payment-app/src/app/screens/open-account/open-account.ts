import {Component, inject} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';
import {AppConfigService} from '../../services/config/app-config.service';
import {UserService} from '../../services/user/user.service';

@Component({
  selector: 'app-open-account',
  templateUrl: './open-account.html',
  imports: [FormsModule],
})
export class OpenAccount {
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private readonly config = inject(AppConfigService);

  readonly providers = this.config.providers;
  readonly demoRecipient = this.config.demoRecipient;

  selectedProviderId = this.providers[0].id;
  name = 'Alice';
  taxId = '11111111111';
  errorMessage = '';
  loading = false;

  openAccount(): void {
    this.errorMessage = '';
    if (!this.name.trim() || !this.taxId.trim()) {
      this.errorMessage = 'Enter a name and tax ID.';
      return;
    }

    this.loading = true;
    this.config.selectProvider(this.selectedProviderId);
    this.userService.openCustomer(this.name.trim(), this.taxId.trim()).subscribe({
      next: () => void this.router.navigate(['/home']),
      error: () => {
        this.loading = false;
        this.errorMessage = 'Could not open the demo account.';
      },
    });
  }
}
