import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class UserService {

  constructor() { }

  getUser() {
    return {
      name: 'John Doe',
      balance: 1250.75,
    };
  }
}
