import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { finalize } from 'rxjs';
import { PasswordResetApi } from '../core/auth/password-reset-api';
import { toApiError } from '../core/http/api-error';

type Estado = 'ocioso' | 'carregando' | 'enviado' | 'erro';
const ESPERA_REENVIO = 60;

@Component({
  selector: 'app-recuperar-senha-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './recuperar-senha-page.component.html',
  styleUrl: './recuperar-senha-page.component.scss',
})
export class RecuperarSenhaPageComponent {
  private readonly auth = inject(PasswordResetApi);
  private readonly doc = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private timer?: ReturnType<typeof setInterval>;
  private esperaAte = 0;

  // aceita ?email=... (vindo da tela de login)
  readonly email = new FormControl(this.route.snapshot.queryParamMap.get('email') ?? '', {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.email,
      Validators.maxLength(254),
      Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/),
    ],
  });

  readonly estado = signal<Estado>('ocioso');
  readonly erroServidor = signal('');
  readonly reenviando = signal(false);
  readonly espera = signal(0);
  readonly aviso = signal('');

  readonly carregando = computed(() => this.estado() === 'carregando');
  readonly relogio = computed(() => {
    const remaining = this.espera();
    return `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;
  });

  constructor() {
    this.destroyRef.onDestroy(() => clearInterval(this.timer));
  }

  get mostrarErro(): boolean {
    return this.email.touched && this.email.invalid;
  }

  erroEmail(): string {
    if (this.email.hasError('server')) {
      return this.email.getError('server');
    }
    if (this.email.hasError('maxlength')) {
      return 'Informe um e-mail com até 254 caracteres.';
    }
    return this.email.errors?.['required']
      ? 'Informe seu e-mail.'
      : 'Informe um e-mail válido, como nome@exemplo.com.';
  }

  enviar(): void {
    if (
      this.carregando() ||
      this.reenviando() ||
      this.espera() > 0 ||
      this.estado() === 'enviado'
    ) {
      return;
    }
    this.email.setValue(this.email.value.trim());
    this.email.markAsTouched();
    if (this.email.invalid) {
      this.doc.getElementById('rec-email')?.focus();
      return;
    }

    this.estado.set('carregando');
    this.erroServidor.set('');
    const valor = this.email.value;
    this.email.disable();

    this.auth
      .request(valor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.email.enable();
          this.estado.set('enviado');
          this.iniciarEspera();
        },
        error: (cause: unknown) => {
          this.email.enable();
          this.estado.set('erro');
          this.tratarErro(cause, false);
        },
      });
  }

  reenviar(): void {
    if (this.espera() > 0 || this.reenviando() || this.estado() !== 'enviado') {
      return;
    }
    this.reenviando.set(true);
    this.aviso.set('');
    this.auth
      .request(this.email.value)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.reenviando.set(false)),
      )
      .subscribe({
        next: () => {
          this.aviso.set(
            'Nova solicitação recebida. Se houver uma conta, o envio será processado. Confira também o spam.',
          );
          this.iniciarEspera();
        },
        error: (cause: unknown) => {
          this.tratarErro(cause, true);
        },
      });
  }

  usarOutroEmail(): void {
    if (this.reenviando()) {
      return;
    }
    this.aviso.set('');
    this.erroServidor.set('');
    this.email.reset('');
    this.estado.set('ocioso');
    afterNextRender(() => this.doc.getElementById('rec-email')?.focus(), {
      injector: this.injector,
    });
  }

  private tratarErro(cause: unknown, reenvio: boolean): void {
    const error = toApiError(cause);
    const message =
      error.status === 429
        ? 'Limite de solicitações atingido. Aguarde antes de tentar novamente.'
        : error.message;

    if (reenvio) {
      this.aviso.set(message);
    } else {
      this.erroServidor.set(message);
    }
    if (error.status === 429) {
      this.iniciarEspera(Math.max(1, error.retryAfterSeconds ?? ESPERA_REENVIO));
    }
    if (!reenvio && (error.status === 400 || error.status === 422)) {
      const field = error.fieldErrors.find((item) => item.field === 'email');
      if (field) {
        this.email.setErrors({ server: field.message });
        this.email.markAsTouched();
        this.doc.getElementById('rec-email')?.focus();
      }
    }
  }

  private iniciarEspera(seconds = ESPERA_REENVIO): void {
    clearInterval(this.timer);
    this.esperaAte = Date.now() + seconds * 1000;
    this.atualizarEspera();
    this.timer = setInterval(() => this.atualizarEspera(), 1000);
  }

  private atualizarEspera(): void {
    this.espera.set(Math.max(0, Math.ceil((this.esperaAte - Date.now()) / 1000)));
    if (this.espera() === 0) {
      clearInterval(this.timer);
    }
  }
}
