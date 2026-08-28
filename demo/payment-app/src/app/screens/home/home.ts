import {Component, Signal} from '@angular/core';
import {Router} from '@angular/router';
import {User, UserService} from '../../services/user/user.service';
import {DecimalPipe} from '@angular/common';

@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  imports: [DecimalPipe],
  styleUrls: ['./home.css']
})
export class Home {
  customer: Signal<User>;

  constructor(private router: Router, private userService: UserService) {
    this.customer = this.userService.getUser();

    for (let key of this.customer().pixKeys) {
      console.log(`Pix Key: ${key}`);
    }
  }

  goToTransfer() {
    this.router.navigate(['transfer']).catch(error => console.log(error));
  }

  goToCreatePixKey() {
    this.router.navigate(['/create-pix-key']).catch(error => console.log(error));
  }

  goToInfo() {
    this.router.navigate(['/info']).catch(error => console.log(error));
  }

  logout() {
    this.userService.logout();
  }

  getFirstLetter(name: string): string {
    return name ? name.charAt(0).toUpperCase() : 'U';
  }
}
