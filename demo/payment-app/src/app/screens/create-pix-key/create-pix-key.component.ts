import {Component} from '@angular/core';
import {Router} from '@angular/router';
import {FormsModule} from '@angular/forms';
import {NgIf} from '@angular/common';
import {UserService} from '../../services/user/user.service';

@Component({
  selector: 'app-create-pix-key',
  templateUrl: './create-pix-key.component.html',
  imports: [
    FormsModule,
    NgIf
  ]
})
export class CreatePixKeyComponent {
  constructor(private router: Router, private userService: UserService) {
  }

  pixKey: string = '';
  errorMessage: string = '';

  savePixKey() {
    this.errorMessage = '';

    if (!this.pixKey.trim()) {
      this.errorMessage = 'Please enter a valid PIX key.';
      return;
    }

    this.userService.createPixKey(this.pixKey).subscribe(
      () => {
        console.log('PIX Key created:', this.pixKey);
        this.router.navigate(['/home']).catch(error => console.log(error));
      }
    )
  }

  cancel() {
    this.router.navigate(['/home']).catch(error => console.log(error));
  }
}
