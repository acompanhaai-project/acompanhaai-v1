import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { AuthService } from '../shared/auth.service';

type Estado = 'ocioso' | 'carregando' | 'enviado' | 'erro';
const ESPERA_REENVIO = 30; // segundos

@Component({
  selector: 'app-recuperar-senha-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './recuperar-senha-page.component.html',
  styleUrl: './recuperar-senha-page.component.scss',
})
export class RecuperarSenhaPageComponent {
  private readonly auth = inject(AuthService);
  private readonly doc = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private timer?: ReturnType<typeof setInterval>;

  // aceita ?email=... (vindo da tela de login)
  readonly email = new FormControl(this.route.snapshot.queryParamMap.get('email') ?? '', {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.email,
      Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/),
    ],
  });

  readonly estado = signal<Estado>('ocioso');
  readonly erroServidor = signal('');
  readonly reenviando = signal(false);
  readonly espera = signal(0);
  readonly aviso = signal('');

  readonly carregando = computed(() => this.estado() === 'carregando');
  readonly relogio = computed(() => `0:${String(this.espera()).padStart(2, '0')}`);

  constructor() {
    inject(DestroyRef).onDestroy(() => clearInterval(this.timer));
  }

  get mostrarErro(): boolean {
    return this.email.touched && this.email.invalid;
  }

  erroEmail(): string {
    return this.email.errors?.['required']
      ? 'Informe seu e-mail.'
      : 'Informe um e-mail válido, como nome@exemplo.com.';
  }

  enviar(): void {
    if (this.carregando()) {
      return;
    }
    this.email.markAsTouched();
    if (this.email.invalid) {
      this.doc.getElementById('rec-email')?.focus();
      return;
    }

    this.estado.set('carregando');
    this.erroServidor.set('');
    const valor = this.email.value;
    this.email.disable();

    this.auth.solicitarRecuperacao(valor).subscribe({
      next: () => {
        this.email.enable();
        this.estado.set('enviado');
        this.iniciarEspera();
      },
      error: () => {
        this.email.enable();
        this.estado.set('erro');
        this.erroServidor.set(
          'Não foi possível enviar o link agora. Verifique sua internet e tente novamente.',
        );
      },
    });
  }

  reenviar(): void {
    if (this.espera() > 0 || this.reenviando()) {
      return;
    }
    this.reenviando.set(true);
    this.aviso.set('');
    this.auth.solicitarRecuperacao(this.email.value).subscribe({
      next: () => {
        this.reenviando.set(false);
        this.aviso.set('Link reenviado. Confira também a caixa de spam.');
        this.iniciarEspera();
      },
      error: () => {
        this.reenviando.set(false);
        this.aviso.set('Não foi possível reenviar agora. Tente novamente em instantes.');
      },
    });
  }

  usarOutroEmail(): void {
    clearInterval(this.timer);
    this.espera.set(0);
    this.aviso.set('');
    this.email.reset('');
    this.estado.set('ocioso');
    setTimeout(() => this.doc.getElementById('rec-email')?.focus());
  }

  private iniciarEspera(): void {
    clearInterval(this.timer);
    this.espera.set(ESPERA_REENVIO);
    this.timer = setInterval(() => {
      this.espera.update((v) => v - 1);
      if (this.espera() <= 0) clearInterval(this.timer);
    }, 1000);
  }
}
