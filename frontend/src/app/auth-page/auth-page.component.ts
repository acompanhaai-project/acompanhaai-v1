import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NeonCardDirective } from '../shared/neon-card.directive';

@Component({
  selector: 'app-auth-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auth-page.component.html',
  styleUrl: './auth-page.component.scss',
})
export class AuthPageComponent {
  readonly email = new FormControl('', {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.email,
      Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/),
    ],
  });

  readonly mensagem = signal('');

  get mostrarErro(): boolean {
    return this.email.touched && this.email.invalid;
  }

  loginComGoogle(): void {
    console.log('Login com Google');
    this.mensagem.set('Abrindo login com Google…');
  }
  loginComApple(): void {
    console.log('Login com Apple');
    this.mensagem.set('Abrindo login com Apple…');
  }

  onSubmit(): void {
    this.email.markAsTouched();
    if (this.email.invalid) {
      return;
    }
    console.log('E-mail:', this.email.value);
    this.mensagem.set(`Código enviado para ${this.email.value}.`);
  }
}
