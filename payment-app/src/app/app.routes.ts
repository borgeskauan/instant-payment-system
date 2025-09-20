import { Routes } from '@angular/router';
import {Home} from './screens/home/home';
import {Transfer} from './screens/transfer/transfer';

export const routes: Routes = [
  { path: '', redirectTo: '/home', pathMatch: 'full' }, // Default route
  { path: 'home', component: Home },
  { path: 'transfer', component: Transfer },
  { path: '**', redirectTo: '/home' } // Wildcard route for 404
];
