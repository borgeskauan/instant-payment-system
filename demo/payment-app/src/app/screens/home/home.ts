import {Component, inject} from '@angular/core';
import {DecimalPipe} from '@angular/common';
import {Router} from '@angular/router';
import {UserService} from '../../services/user/user.service';

@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  imports: [DecimalPipe],
})
export class Home {
  private readonly router = inject(Router);
  private readonly userService = inject(UserService);

  readonly customer = this.userService.user;

  constructor() {
    if (!this.customer()) {
      void this.router.navigate(['/start']);
    }
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

  getFirstLetter(name: string): string {
    return name ? name.charAt(0).toUpperCase() : 'U';
  }
}
