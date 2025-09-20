import { Component } from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Router} from '@angular/router';

@Component({
  selector: 'app-transfer',
  imports: [
    FormsModule
  ],
  templateUrl: './transfer.html',
  styleUrl: './transfer.css'
})
export class Transfer {
  constructor(private router: Router) {}

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

    setTimeout(() => {
      this.loading = false;

      if (this.pixKey === 'invalid@pix') {
        this.errorMessage = 'PIX key not found. Please try again.';
        return;
      }

      this.recipient = {
        name: 'Alice Johnson',
        taxId: '123.456.789-00',
        institution: 'Bank of Angular',
        found: true,
      };
      this.step = 'amount';
    }, 1500);
  }

  backToPix() {
    this.step = 'pix';
    this.pixKey = '';
    this.recipient = { name: '', taxId: '', institution: '', found: false };
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
    this.recipient = { name: '', taxId: '', institution: '', found: false };
  }

  goHome() {
    this.router.navigate(['/home']).catch((error: any) => console.log(error));
  }
}
