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
import { RouterLink } from '@angular/router';
import { DOCUMENT } from '@angular/common';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { SessionApi } from '../core/auth/session-api';
import { ApiError, toApiError } from '../core/http/api-error';
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
  private readonly session = inject(SessionApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly doc = inject(DOCUMENT);

  readonly form = new FormGroup(
    {
      nome: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.minLength(3),
          Validators.maxLength(100),
          Validators.pattern(/^\S+(\s+\S+)+$/),
        ],
      }),
      email: new FormControl('', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.email,
          Validators.maxLength(254),
          Validators.pattern(/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/),
        ],
      }),
      senha: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(128), senhaForte],
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
    if (e?.['server']) {
      return e['server'];
    }
    if (e?.['maxlength']) {
      return 'Informe um nome com até 100 caracteres.';
    }
    return e?.['required'] ? 'Informe seu nome completo.' : 'Informe nome e sobrenome.';
  }

  erroEmail(): string {
    const e = this.form.controls.email.errors;
    if (e?.['server']) {
      return e['server'];
    }
    if (e?.['maxlength']) {
      return 'Informe um e-mail com até 254 caracteres.';
    }
    if (e?.['emUso']) {
      return 'Este e-mail já está cadastrado. Tente entrar na sua conta.';
    }
    return e?.['required']
      ? 'Informe seu e-mail.'
      : 'Informe um e-mail válido, como nome@exemplo.com.';
  }

  erroSenha(): string {
    const errors = this.form.controls.senha.errors;
    if (errors?.['server']) {
      return errors['server'];
    }
    if (errors?.['maxlength']) {
      return 'A senha deve ter no máximo 128 caracteres.';
    }
    return errors?.['required']
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
    this.form.controls.nome.setValue(this.form.controls.nome.value.trim());
    this.form.controls.email.setValue(this.form.controls.email.value.trim());
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

    this.session
      .register({ name: dados.nome, email: dados.email, password: dados.senha })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.form.controls.senha.reset('');
          this.form.controls.confirma.reset('');
          this.verSenha.set(false);
          this.verConfirma.set(false);
          this.estado.set('sucesso');
        },
        error: (cause: unknown) => this.tratarErro(toApiError(cause)),
      });
  }

  private tratarErro(error: ApiError): void {
    this.form.enable();
    this.estado.set('erro');
    this.erroServidor.set(error.message);

    if (error.status === 409) {
      this.form.controls.email.setErrors({ emUso: true });
      this.form.controls.email.markAsTouched();
      this.doc.getElementById('cad-email')?.focus();
      return;
    }

    if (error.status === 400 || error.status === 422) {
      const fields = {
        name: { control: this.form.controls.nome, id: 'cad-nome' },
        email: { control: this.form.controls.email, id: 'cad-email' },
        password: { control: this.form.controls.senha, id: 'cad-senha' },
      };
      let firstInvalid: string | undefined;

      for (const fieldError of error.fieldErrors) {
        if (!Object.hasOwn(fields, fieldError.field)) {
          continue;
        }
        const field = fields[fieldError.field as keyof typeof fields];
        field.control.setErrors({ ...field.control.errors, server: fieldError.message });
        field.control.markAsTouched();
        firstInvalid ??= field.id;
      }

      if (firstInvalid) {
        this.doc.getElementById(firstInvalid)?.focus();
      }
    }
  }
}
