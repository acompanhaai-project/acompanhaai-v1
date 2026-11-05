import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { DOCUMENT } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { AuthService } from '../shared/auth.service';
import { senhaForte, senhasIguais } from '../shared/senha';
import { RequisitosSenhaComponent } from '../shared/requisitos-senha.component';

type Estado = 'validando' | 'invalido' | 'ocioso' | 'carregando' | 'sucesso' | 'erro';

@Component({
  selector: 'app-redefinir-senha-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective, RequisitosSenhaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './redefinir-senha-page.component.html',
  styleUrl: './redefinir-senha-page.component.scss',
})
export class RedefinirSenhaPageComponent {
  private readonly auth = inject(AuthService);
  private readonly doc = inject(DOCUMENT);
  private readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token') ?? '';

  readonly form = new FormGroup(
    {
      senha: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, senhaForte],
      }),
      confirma: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    },
    { validators: senhasIguais },
  );

  readonly estado = signal<Estado>('validando');
  readonly erroServidor = signal('');
  readonly verSenha = signal(false);
  readonly verConfirma = signal(false);
  readonly senhaValor = toSignal(this.form.controls.senha.valueChanges, { initialValue: '' });
  readonly carregando = computed(() => this.estado() === 'carregando');

  constructor() {
    // O link precisa ser válido antes de mostrar o formulário.
    this.auth
      .validarToken(this.token)
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: () => {
          this.estado.set('ocioso');
          setTimeout(() => this.doc.getElementById('red-senha')?.focus());
        },
        error: () => this.estado.set('invalido'),
      });
  }

  invalido(c: AbstractControl): boolean {
    return c.touched && c.invalid;
  }

  get confirmaInvalida(): boolean {
    const c = this.form.controls.confirma;
    return c.touched && (c.invalid || this.form.hasError('senhasDiferentes'));
  }

  erroSenha(): string {
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
    if (this.carregando()) {
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

    this.auth.redefinirSenha(this.token, senha).subscribe({
      next: () => this.estado.set('sucesso'),
      error: (err: Error) => {
        this.form.enable();
        if (err.message === 'TOKEN_INVALIDO') {
          this.estado.set('invalido');
          return;
        }
        this.estado.set('erro');
        this.erroServidor.set(
          'Sem conexão com o servidor. Verifique sua internet e tente novamente.',
        );
      },
    });
  }
}
