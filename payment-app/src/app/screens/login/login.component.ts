import {Component} from '@angular/core';
import {Router} from '@angular/router';
import {UserService} from '../../services/user/user.service';
import {FormsModule} from '@angular/forms';
import {NgIf} from '@angular/common';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  imports: [
    FormsModule,
    NgIf
  ]
})
export class LoginComponent {
  constructor(private userService: UserService, private router: Router) {
  }

  name: string = '';
  taxId: string = '';
  errorMessage: string = '';

  login() {
    this.errorMessage = '';

    if (!this.name.trim() || !this.taxId.trim()) {
      this.errorMessage = 'Please enter both name and tax ID.';
      return;
    }

    this.userService.login(this.name.trim(), this.taxId.trim()).subscribe(
      () => this.router.navigate(['/home']).catch(error => console.log(error))
    );
  }
}
