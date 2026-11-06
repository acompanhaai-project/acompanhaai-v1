import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { DOCUMENT } from '@angular/common';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { AuthService } from '../shared/auth.service';
import { senhaForte, senhasIguais } from '../shared/senha';
import { RequisitosSenhaComponent } from '../shared/requisitos-senha.component';

type Estado = 'ocioso' | 'carregando' | 'sucesso' | 'erro';

@Component({
  selector: 'app-signup-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective, RequisitosSenhaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './signup-page.component.html',
  styleUrl: './signup-page.component.scss',
})
export class SignupPageComponent {
  private readonly auth = inject(AuthService);
  private readonly doc = inject(DOCUMENT);

  readonly form = new FormGroup(
    {
      nome: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.minLength(3),
          Validators.pattern(/^\S+(\s+\S+)+$/),
        ],
      }),
      email: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.email,
          Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/),
        ],
      }),
      senha: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, senhaForte],
      }),
      confirma: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    },
    { validators: senhasIguais },
  );

  readonly estado = signal<Estado>('ocioso');
  readonly erroServidor = signal('');
  readonly verSenha = signal(false);
  readonly verConfirma = signal(false);

  readonly senhaValor = toSignal(this.form.controls.senha.valueChanges, { initialValue: '' });

  readonly carregando = computed(() => this.estado() === 'carregando');

  /* ---------- mensagens de erro ---------- */
  invalido(c: AbstractControl): boolean {
    return c.touched && c.invalid;
  }

  get confirmaInvalida(): boolean {
    const c = this.form.controls.confirma;
    return c.touched && (c.invalid || this.form.hasError('senhasDiferentes'));
  }

  erroNome(): string {
    const e = this.form.controls.nome.errors;
    return e?.['required'] ? 'Informe seu nome completo.' : 'Informe nome e sobrenome.';
  }

  erroEmail(): string {
    const e = this.form.controls.email.errors;
    if (e?.['emUso']) {
      return 'Este e-mail já está cadastrado. Tente entrar na sua conta.';
    }
    return e?.['required']
      ? 'Informe seu e-mail.'
      : 'Informe um e-mail válido, como nome@exemplo.com.';
  }

  erroSenha(): string {
    return this.form.controls.senha.errors?.['required']
      ? 'Crie uma senha.'
      : 'A senha ainda não atende a todos os requisitos.';
  }

  erroConfirma(): string {
    return this.form.controls.confirma.errors?.['required']
      ? 'Confirme sua senha.'
      : 'As senhas não coincidem.';
  }

  /* ---------- envio ---------- */
  enviar(): void {
    if (this.carregando()) {
      return;
    }
    this.form.markAllAsTouched();

    if (this.form.invalid) {
      const c = this.form.controls;
      const primeiro =
        (Object.keys(c) as (keyof typeof c)[]).find((k) => c[k].invalid) ?? 'confirma';
      this.doc.getElementById(`cad-${primeiro}`)?.focus();
      return;
    }

    this.estado.set('carregando');
    this.erroServidor.set('');
    const dados = this.form.getRawValue();
    this.form.disable();

    this.auth.cadastrar({ nome: dados.nome, email: dados.email, senha: dados.senha }).subscribe({
      next: () => this.estado.set('sucesso'),
      error: (err: Error) => {
        this.form.enable();
        this.estado.set('erro');
        if (err.message === 'EMAIL_EM_USO') {
          this.form.controls.email.setErrors({ emUso: true });
          this.form.controls.email.markAsTouched();
          this.erroServidor.set('Não foi possível criar a conta com este e-mail.');
          this.doc.getElementById('cad-email')?.focus();
        } else {
          this.erroServidor.set(
            'Sem conexão com o servidor. Verifique sua internet e tente novamente.',
          );
        }
      },
    });
  }
}
