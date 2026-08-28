import {Component} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';
import {UserService} from '../../services/user/user.service';
import {AppConfigService} from '../../services/config/app-config.service';

@Component({
  selector: 'app-create-pix-key',
  templateUrl: './create-pix-key.component.html',
  imports: [FormsModule],
})
export class CreatePixKeyComponent {
  pixKey = '';
  errorMessage = '';
  loading = false;
  readonly customerName: string;
  readonly providerName: string;

  constructor(
    private readonly router: Router,
    private readonly userService: UserService,
    config: AppConfigService,
  ) {
    this.providerName = config.provider().name;
    this.customerName = this.userService.user()?.name ?? '';
    if (!this.customerName) {
      void this.router.navigate(['/start']);
    }
  }

  savePixKey(): void {
    const pixKey = this.pixKey.trim();
    this.errorMessage = '';
    if (!pixKey) {
      this.errorMessage = 'Enter a PIX key.';
      return;
    }

    this.loading = true;
    this.userService.createPixKey(pixKey).subscribe({
      next: () => void this.router.navigate(['/home']),
      error: () => {
        this.loading = false;
        this.errorMessage = 'Could not register this PIX key.';
      },
    });
  }

  cancel(): void {
    void this.router.navigate(['/home']);
  }
}
