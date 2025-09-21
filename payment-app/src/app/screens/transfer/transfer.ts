import {Component} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';
import {PspService} from '../../services/psp/psp.service';

@Component({
  selector: 'app-transfer',
  imports: [
    FormsModule
  ],
  templateUrl: './transfer.html',
  styleUrl: './transfer.css'
})
export class Transfer {
  constructor(private router: Router, private pspService: PspService) {
  }

  step: 'pix' | 'amount' | 'confirm' = 'pix';
  pixKey: string = '';
  loading: boolean = false;
  errorMessage: string = '';

  userBalance: number = 500;

  recipient = {
    name: '',
    taxId: '',
    institution: '',
    found: false,
  };

  amount: number | null = null;

  submitPixKey() {
    this.errorMessage = '';
    this.loading = true;

    this.pspService.searchPixKey(this.pixKey).subscribe({
      next: result => {
        this.recipient = {
          name: result.name,
          taxId: result.taxId,
          institution: result.institution,
          found: true,
        };
        this.step = 'amount';
        this.loading = false;
      },
      error: err => {
        this.loading = false;
        this.errorMessage = 'An error occurred while searching for the PIX key. Please try again.';
        console.error('Error searching PIX key:', err);
      }
    });
  }

  backToPix() {
    this.step = 'pix';
    this.pixKey = '';
    this.recipient = {name: '', taxId: '', institution: '', found: false};
    this.amount = null;
    this.errorMessage = '';
  }

  proceedToConfirm() {
    this.errorMessage = '';

    if (!this.amount || this.amount <= 0) {
      this.errorMessage = 'Please enter a valid amount.';
      return;
    }

    this.step = 'confirm';
  }

  backToAmount() {
    this.step = 'amount';
    this.errorMessage = '';
  }

  confirmTransfer() {
    this.errorMessage = '';

    if (this.amount && this.amount > this.userBalance) {
      this.errorMessage = 'Insufficient funds to complete the transfer.';
      return;
    }

    alert(`✅ Transfer of $${this.amount} to ${this.recipient.name} confirmed!`);

    if (this.amount) {
      this.userBalance -= this.amount;
    }

    this.step = 'pix';
    this.pixKey = '';
    this.amount = null;
    this.recipient = {name: '', taxId: '', institution: '', found: false};
  }

  goHome() {
    this.router.navigate(['/home']).catch((error: any) => console.log(error));
  }
}
