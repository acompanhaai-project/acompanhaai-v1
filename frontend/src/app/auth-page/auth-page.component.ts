import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { SessionApi } from '../core/auth/session-api';
import { SessionState } from '../core/auth/session-state';
import { SessionAccess } from '../core/auth/session-access';
import { safeReturnUrl } from '../core/auth/return-url';
import { ApiError, toApiError } from '../core/http/api-error';
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
  private readonly session = inject(SessionApi);
  private readonly state = inject(SessionState);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly access = inject(SessionAccess);

  readonly email = new FormControl('', {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.email,
      Validators.maxLength(254),
      Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/),
    ],
  });
  readonly senha = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(128)],
  });
  readonly form = new FormGroup({ email: this.email, senha: this.senha });

  readonly etapa = signal<'email' | 'senha'>('email');
  readonly carregando = signal(false);
  readonly saindo = signal(false);
  readonly verSenha = signal(false);
  readonly mensagem = signal('');
  readonly erroServidor = signal('');
  readonly autenticado = this.state.authenticated;
  readonly usuario = this.state.user;
  readonly areaPrivada = this.route.snapshot.data['requiresAuth'] === true;
  readonly verificando = this.access.checking;
  readonly erroVerificacao = this.access.error;

  constructor() {
    if (this.route.snapshot.queryParamMap.get('reason') === 'expired') {
      this.mensagem.set('Sua sessão expirou. Entre novamente para continuar.');
    } else if (
      this.route.snapshot.queryParamMap.get('notice') === 'logout' &&
      this.state.status() === 'anonymous'
    ) {
      this.mensagem.set('Sessão encerrada.');
    }
  }

  get mostrarErro(): boolean {
    return this.email.touched && this.email.invalid;
  }

  erroEmail(): string {
    if (this.email.hasError('server')) {
      return this.email.getError('server');
    }
    return this.email.hasError('maxlength')
      ? 'Informe um e-mail com até 254 caracteres.'
      : 'Informe um e-mail válido, como nome@exemplo.com.';
  }

  erroSenha(): string {
    if (this.senha.hasError('server')) {
      return this.senha.getError('server');
    }
    return this.senha.hasError('maxlength')
      ? 'A senha deve ter no máximo 128 caracteres.'
      : 'Informe sua senha.';
  }

  loginComGoogle(): void {
    this.mensagem.set('O acesso com Google ainda não está disponível.');
  }

  loginComApple(): void {
    this.mensagem.set('O acesso com Apple ainda não está disponível.');
  }

  onSubmit(): void {
    if (
      this.areaPrivada ||
      this.carregando() ||
      this.saindo() ||
      this.autenticado() ||
      this.verificando() ||
      this.erroVerificacao()
    ) {
      return;
    }
    this.mensagem.set('');

    if (this.etapa() === 'email') {
      this.email.setValue(this.email.value.trim());
      this.email.markAsTouched();
      if (this.email.invalid) {
        this.focar('email');
        return;
      }
      this.erroServidor.set('');
      this.etapa.set('senha');
      this.focar('login-senha');
      return;
    }

    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focar('login-senha');
      return;
    }

    const credentials = { email: this.email.value, password: this.senha.value };
    this.carregando.set(true);
    this.erroServidor.set('');
    this.form.disable();

    this.session
      .login(credentials)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.carregando.set(false)),
      )
      .subscribe({
        next: () => {
          this.form.enable();
          this.limparSenha();
          this.etapa.set('email');
          this.navegarAposLogin();
        },
        error: (cause: unknown) => {
          this.form.enable();
          this.limparSenha();
          this.tratarErro(toApiError(cause));
        },
      });
  }

  alterarEmail(): void {
    if (this.carregando() || this.saindo()) {
      return;
    }
    this.limparSenha();
    this.etapa.set('email');
    this.erroServidor.set('');
    this.mensagem.set('');
    this.focar('email');
  }

  sair(): void {
    if (this.saindo() || !this.autenticado()) {
      return;
    }
    this.saindo.set(true);
    this.erroServidor.set('');
    this.mensagem.set('');

    this.session
      .logout()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saindo.set(false)),
      )
      .subscribe({
        next: () => this.voltarAoInicio('Sessão encerrada.'),
        error: () => {
          if (this.state.status() === 'anonymous') {
            this.voltarAoInicio(
              'Sessão encerrada. Tente novamente se houver falha de conexão no próximo acesso.',
            );
          } else {
            this.form.enable();
            this.form.reset();
            this.limparSenha();
            this.etapa.set('email');
            this.erroServidor.set(
              'Não foi possível confirmar o encerramento da sessão. Tente entrar novamente.',
            );
          }
        },
      });
  }

  verificarNovamente(): void {
    if (this.verificando()) {
      return;
    }
    this.erroServidor.set('');
    this.access
      .check()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (user) => {
          if (user) {
            this.navegarAposLogin();
          } else if (this.areaPrivada) {
            void this.router.navigate(['/entrar'], {
              queryParams: { returnUrl: safeReturnUrl(this.router.url), reason: 'expired' },
              replaceUrl: true,
            });
          }
        },
        error: () => this.limparSenha(),
      });
  }

  private navegarAposLogin(): void {
    const target = this.areaPrivada
      ? this.router.url
      : this.route.snapshot.queryParamMap.get('returnUrl');
    void this.router.navigateByUrl(safeReturnUrl(target), { replaceUrl: true });
  }

  private voltarAoInicio(message: string): void {
    this.form.enable();
    this.form.reset();
    this.verSenha.set(false);
    this.etapa.set('email');
    this.mensagem.set(message);
    if (this.areaPrivada) {
      void this.router.navigateByUrl('/entrar?notice=logout', { replaceUrl: true });
    } else {
      this.focar('email');
    }
  }

  private limparSenha(): void {
    this.senha.reset('');
    this.verSenha.set(false);
  }

  private tratarErro(error: ApiError): void {
    this.erroServidor.set(error.status === 401 ? 'E-mail ou senha inválidos.' : error.message);
    this.focar('login-senha');

    if (error.status === 400 || error.status === 422) {
      for (const field of error.fieldErrors) {
        if (field.field === 'email') {
          this.email.setErrors({ server: field.message });
          this.email.markAsTouched();
          this.etapa.set('email');
          this.focar('email');
        } else if (field.field === 'password') {
          this.senha.setErrors({ server: field.message });
          this.senha.markAsTouched();
        }
      }
    }
  }

  private focar(id: string): void {
    afterNextRender(() => this.document.getElementById(id)?.focus(), { injector: this.injector });
  }
}
