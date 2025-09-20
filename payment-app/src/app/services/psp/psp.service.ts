import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class PspService {
  constructor() { }

  requestTransfer(amount: number, toAccount: string): Promise<boolean> {
    return new Promise((resolve) => {
      setTimeout(() => {
        resolve(true);
      }, 2000);
    });
  }

  searchPixKey(pixKey: string): Promise<{name: string, taxId: string, institution: string} | null> {
    return new Promise((resolve) => {
      setTimeout(() => {
        if (pixKey === 'invalid@pix') {
          resolve(null);
        } else {
          resolve({
            name: 'Alice Johnson',
            taxId: '123.456.789-00',
            institution: 'Bank of Angular'
          });
        }
      }, 1500);
    });
  }
}
