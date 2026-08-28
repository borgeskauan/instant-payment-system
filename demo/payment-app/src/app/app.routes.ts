import {Routes} from '@angular/router';
import {CreatePixKeyComponent} from './screens/create-pix-key/create-pix-key.component';
import {Home} from './screens/home/home';
import {OpenAccount} from './screens/open-account/open-account';
import {Transfer} from './screens/transfer/transfer';

export const routes: Routes = [
  {path: '', redirectTo: '/start', pathMatch: 'full'},
  {path: 'start', component: OpenAccount},
  {path: 'home', component: Home},
  {path: 'transfer', component: Transfer},
  {path: 'create-pix-key', component: CreatePixKeyComponent},
  {path: '**', redirectTo: '/start'},
];
