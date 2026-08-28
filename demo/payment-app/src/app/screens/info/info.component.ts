import { Component, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AppConfigService } from '../../services/config/app-config.service';
import { PspClientService, PspInfo } from '../../services/psp/client/psp-client.service';
import { UserService } from '../../services/user/user.service';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-info',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './info.component.html',
  styleUrls: ['./info.component.css']
})
export class InfoComponent implements OnInit {
  pspInfo = signal<PspInfo | null>(null);
  loading = signal<boolean>(true);
  errorMessage = signal<string>('');

  constructor(
    private router: Router,
    public appConfigService: AppConfigService,
    private pspClient: PspClientService,
    public userService: UserService
  ) {}

  ngOnInit() {
    this.loadPspInfo();
  }

  loadPspInfo() {
    this.loading.set(true);
    this.errorMessage.set('');
    
    this.pspClient.getInfo().subscribe({
      next: (info) => {
        this.pspInfo.set(info);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Failed to load PSP info:', error);
        this.errorMessage.set('Failed to load PSP information');
        this.loading.set(false);
      }
    });
  }

  goBack() {
    this.router.navigate(['/home']).catch(error => console.log(error));
  }
}
