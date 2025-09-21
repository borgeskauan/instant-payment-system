import {Component, OnInit} from '@angular/core';
import {DecimalPipe, NgForOf, NgIf} from "@angular/common";
import {Router} from '@angular/router';
import {User, UserService} from '../../services/user/user.service';

@Component({
  selector: 'app-home',
  imports: [
    DecimalPipe,
    NgForOf,
    NgIf
  ],
  templateUrl: './home.html',
  styleUrl: './home.css'
})
export class Home implements OnInit {
  customer?: User;

  pixKeys: string[] = [];

  constructor(private router: Router, private userService: UserService) {
  }

  ngOnInit(): void {
    this.customer = this.userService.getUser();
    this.pixKeys = this.customer.pixKeys || [];
  }

  goToTransfer() {
    this.router.navigate(['transfer']).catch(error => console.log(error));
  }

  goToCreatePixKey() {
    this.router.navigate(['/create-pix-key']).catch(error => console.log(error));
  }

  logout() {
    this.userService.logout();
  }
}
