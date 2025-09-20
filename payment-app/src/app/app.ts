import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import {DecimalPipe} from '@angular/common';
import {Home} from './screens/home/home';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, DecimalPipe, Home],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  protected readonly title = signal('payment-app');
}
