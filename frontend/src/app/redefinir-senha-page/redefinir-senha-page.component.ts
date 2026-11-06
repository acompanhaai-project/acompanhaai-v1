import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { DOCUMENT, Location } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { PasswordResetApi } from '../core/auth/password-reset-api';
import { toApiError } from '../core/http/api-error';
import { senhaForte, senhasIguais } from '../shared/senha';
import { RequisitosSenhaComponent } from '../shared/requisitos-senha.component';

type Estado = 'invalido' | 'ocioso' | 'carregando' | 'sucesso' | 'erro';

@Component({
  selector: 'app-redefinir-senha-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective, RequisitosSenhaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './redefinir-senha-page.component.html',
  styleUrl: './redefinir-senha-page.component.scss',
})
export class RedefinirSenhaPageComponent {
  private readonly auth = inject(PasswordResetApi);
  private readonly doc = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);
  private token = '';
  private pending?: Subscription;
  private timer?: ReturnType<typeof setInterval>;
  private esperaAte = 0;

  readonly form = new FormGroup(
    {
      senha: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(128), senhaForte],
      }),
      confirma: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    },
    { validators: senhasIguais },
  );

  readonly estado = signal<Estado>('invalido');
  readonly erroServidor = signal('');
  readonly verSenha = signal(false);
  readonly verConfirma = signal(false);
  readonly senhaValor = toSignal(this.form.controls.senha.valueChanges, { initialValue: '' });
  readonly carregando = computed(() => this.estado() === 'carregando');
  readonly espera = signal(0);
  readonly relogio = computed(() => {
    const remaining = this.espera();
    return `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;
  });

  constructor() {
    this.receberToken();
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.receberToken());
    this.destroyRef.onDestroy(() => {
      clearInterval(this.timer);
      this.pending?.unsubscribe();
      this.token = '';
    });
  }

  private receberToken(): void {
    const fragment = new URLSearchParams(this.route.snapshot.fragment ?? '');
    const query = this.route.snapshot.queryParamMap;
    const fragmentToken = fragment.get('token');
    const queryToken = query.get('token');
    const ambiguous =
      fragment.getAll('token').length > 1 ||
      query.getAll('token').length > 1 ||
      (fragmentToken !== null && queryToken !== null && fragmentToken !== queryToken);
    const received = fragmentToken ?? queryToken ?? '';
    this.removerTokenDaUrl();
    if (!ambiguous && received === this.token && /^[A-Za-z0-9_-]{43}$/.test(received)) {
      return;
    }
    this.pending?.unsubscribe();
    this.form.enable();
    this.limparSegredos();
    this.erroServidor.set('');
    if (!ambiguous && /^[A-Za-z0-9_-]{43}$/.test(received)) {
      this.token = received;
      this.estado.set('ocioso');
    } else {
      this.estado.set('invalido');
    }
  }

  invalido(c: AbstractControl): boolean {
    return c.touched && c.invalid;
  }

  get confirmaInvalida(): boolean {
    const c = this.form.controls.confirma;
    return c.touched && (c.invalid || this.form.hasError('senhasDiferentes'));
  }

  erroSenha(): string {
    if (this.form.controls.senha.hasError('server')) {
      return this.form.controls.senha.getError('server');
    }
    if (this.form.controls.senha.hasError('maxlength')) {
      return 'A senha deve ter no máximo 128 caracteres.';
    }
    return this.form.controls.senha.errors?.['required']
      ? 'Crie uma nova senha.'
      : 'A senha ainda não atende a todos os requisitos.';
  }

  erroConfirma(): string {
    return this.form.controls.confirma.errors?.['required']
      ? 'Confirme sua nova senha.'
      : 'As senhas não coincidem.';
  }

  enviar(): void {
    if (
      this.carregando() ||
      this.espera() > 0 ||
      !this.token ||
      (this.estado() !== 'ocioso' && this.estado() !== 'erro')
    ) {
      return;
    }
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      const alvo = this.form.controls.senha.invalid ? 'red-senha' : 'red-confirma';
      this.doc.getElementById(alvo)?.focus();
      return;
    }

    this.estado.set('carregando');
    this.erroServidor.set('');
    const { senha } = this.form.getRawValue();
    this.form.disable();

    this.pending = this.auth
      .confirm({ token: this.token, newPassword: senha })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.limparSegredos();
          this.estado.set('sucesso');
        },
        error: (cause: unknown) => {
          const error = toApiError(cause);
          this.form.enable();
          if (
            error.status === 400 ||
            (error.status === 422 && error.fieldErrors.some((field) => field.field === 'token'))
          ) {
            this.limparSegredos();
            this.estado.set('invalido');
            return;
          }
          this.estado.set('erro');
          this.erroServidor.set(
            error.status === 0
              ? 'Não foi possível confirmar a alteração. Tente entrar com a nova senha; se necessário, solicite outro link.'
              : error.message,
          );
          if (error.status === 429) {
            this.iniciarEspera(Math.max(1, error.retryAfterSeconds ?? 60));
          }
          const passwordError = error.fieldErrors.find((field) => field.field === 'newPassword');
          if (error.status === 422 && passwordError) {
            this.form.controls.senha.setErrors({ server: passwordError.message });
            this.form.controls.senha.markAsTouched();
            this.doc.getElementById('red-senha')?.focus();
          }
        },
      });
  }

  private removerTokenDaUrl(): void {
    const url = this.location.path();
    const queryStart = url.indexOf('?');
    const path = queryStart < 0 ? url : url.slice(0, queryStart);
    const query = new URLSearchParams(queryStart < 0 ? '' : url.slice(queryStart + 1));
    query.delete('token');
    this.location.replaceState(path, query.toString(), this.doc.defaultView?.history.state);
  }

  private limparSegredos(): void {
    this.token = '';
    this.form.reset();
    this.verSenha.set(false);
    this.verConfirma.set(false);
  }

  private iniciarEspera(seconds: number): void {
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
