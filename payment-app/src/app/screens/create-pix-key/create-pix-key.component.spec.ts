import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CreatePixKeyComponent } from './create-pix-key.component';

describe('CreatePixKeyComponent', () => {
  let component: CreatePixKeyComponent;
  let fixture: ComponentFixture<CreatePixKeyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreatePixKeyComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CreatePixKeyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
