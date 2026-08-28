import { Routes } from '@angular/router';
import {Home} from './screens/home/home';
import {Transfer} from './screens/transfer/transfer';

export const routes: Routes = [
  { path: '', redirectTo: '/login', pathMatch: 'full' }, // Default route
  { path: 'login', loadComponent: () => import('./screens/login/login.component').then(m => m.LoginComponent) },
  { path: 'home', component: Home },
  { path: 'transfer', component: Transfer },
  { path: 'create-pix-key', loadComponent: () => import('./screens/create-pix-key/create-pix-key.component').then(m => m.CreatePixKeyComponent) },
  { path: 'info', loadComponent: () => import('./screens/info/info.component').then(m => m.InfoComponent) },
  { path: '**', redirectTo: '/home' } // Wildcard route for 404
];
