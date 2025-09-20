import {Component, inject} from '@angular/core';
import {DecimalPipe} from "@angular/common";
import {Router} from '@angular/router';
import {routes} from '../../app.routes';

@Component({
  selector: 'app-home',
  imports: [
    DecimalPipe
  ],
  templateUrl: './home.html',
  styleUrl: './home.css'
})
export class Home {
  user = {
    name: 'John Doe',
    balance: 1250.75,
  };

  router: Router = inject(Router);

  goToTransfer() {
    this.router.navigate(['transfer']).catch(error => console.log(error));
  }
}
