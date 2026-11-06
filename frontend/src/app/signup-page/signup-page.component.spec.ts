import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { SignupPageComponent } from './signup-page.component';

const password = 'SenhaFicticia123!';

async function turn(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

describe('Cadastro integrado ao cliente HTTP', () => {
  let fixture: ComponentFixture<SignupPageComponent>;
  let page: SignupPageComponent;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(SignupPageComponent);
    page = fixture.componentInstance;
    backend = TestBed.inject(HttpTestingController);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    fixture.nativeElement.remove();
    backend.verify();
  });

  function fill(): void {
    page.form.setValue({
      nome: '  Maria da Silva  ',
      email: '  maria@example.test  ',
      senha: password,
      confirma: password,
    });
  }

  async function submit() {
    page.enviar();
    await turn();
    backend.expectNone('/api/users');
    const csrf = backend.expectOne('/api/auth/csrf');
    csrf.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-ficticio' });
    return backend.expectOne('/api/users');
  }

  it('envia somente o DTO da API com sessão/CSRF e aguarda a resposta para anunciar sucesso', async () => {
    fill();
    const request = await submit();
    expect(request.request.body).toEqual({
      name: 'Maria da Silva',
      email: 'maria@example.test',
      password,
    });
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-ficticio');
    expect(page.estado()).toBe('carregando');
    expect(page.form.disabled).toBe(true);
    page.enviar();
    backend.expectNone('/api/users');

    request.flush(
      { id: 1, name: 'Maria da Silva', email: 'maria@example.test', photoUrl: null },
      { status: 201, statusText: 'Created' },
    );
    fixture.detectChanges();
    expect(page.estado()).toBe('sucesso');
    expect(page.form.controls.senha.value).toBe('');
    expect(page.form.controls.confirma.value).toBe('');
    expect(TestBed.inject(SessionState).authenticated()).toBe(false);
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Conta criada');
    expect(fixture.nativeElement.textContent).not.toContain('Enviamos um e-mail');
    backend.expectNone('/api/auth/login');
  });

  it('bloqueia formulário inválido e valores acima dos limites antes de chamar a API', () => {
    page.enviar();
    fixture.detectChanges();
    expect(document.activeElement?.id).toBe('cad-nome');
    backend.expectNone('/api/auth/csrf');
    fill();
    page.form.controls.nome.setValue('Maria ' + 'a'.repeat(100));
    page.form.controls.email.setValue('a'.repeat(250) + '@example.test');
    page.form.controls.senha.setValue(password + 'a'.repeat(128));
    page.form.controls.confirma.setValue(page.form.controls.senha.value);
    page.enviar();
    expect(page.form.controls.nome.hasError('maxlength')).toBe(true);
    expect(page.form.controls.email.hasError('maxlength')).toBe(true);
    expect(page.form.controls.senha.hasError('maxlength')).toBe(true);
    backend.expectNone('/api/users');
  });

  it('destaca e-mail duplicado e permite corrigir o campo após 409', async () => {
    fill();
    const request = await submit();
    request.flush({ detail: 'E-mail já cadastrado.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(page.estado()).toBe('erro');
    expect(page.form.enabled).toBe(true);
    expect(page.form.controls.email.hasError('emUso')).toBe(true);
    expect(document.activeElement?.id).toBe('cad-email');
    expect(page.form.controls.senha.value).toBe(password);
    page.form.controls.email.setValue('outra@example.test');
    expect(page.form.controls.email.valid).toBe(true);
    backend.expectNone('/api/users');
  });

  it('mapeia erros 422 aos campos da tela e ignora nomes fora do contrato', async () => {
    fill();
    const request = await submit();
    request.flush(
      {
        detail: 'Dados inválidos.',
        errors: [
          { field: '__proto__', message: 'ignorado' },
          { field: 'name', message: 'Nome rejeitado pela API.' },
          { field: 'password', message: 'Senha rejeitada pela API.' },
        ],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    fixture.detectChanges();
    expect(page.erroNome()).toBe('Nome rejeitado pela API.');
    expect(page.erroSenha()).toBe('Senha rejeitada pela API.');
    expect(document.activeElement?.id).toBe('cad-nome');
    expect(fixture.nativeElement.querySelector('#erro-senha').textContent).toContain(
      'Senha rejeitada',
    );
    expect(page.form.controls.nome.touched).toBe(true);
    page.form.controls.nome.setValue('Maria Souza');
    expect(page.form.controls.nome.hasError('server')).toBe(false);
  });

  it.each([403, 429, 500])(
    'trata falha %i sem anunciar sucesso ou repetir a escrita',
    async (status) => {
      fill();
      const request = await submit();
      request.flush({}, { status, statusText: 'Rejected' });
      fixture.detectChanges();
      expect(page.estado()).toBe('erro');
      expect(page.form.enabled).toBe(true);
      expect(page.erroServidor()).not.toBe('');
      expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
      backend.expectNone('/api/users');
    },
  );

  it('distingue erro de rede e permite uma nova tentativa manual', async () => {
    fill();
    const request = await submit();
    request.error(new ProgressEvent('error'));
    expect(page.estado()).toBe('erro');
    expect(page.erroServidor()).toContain('Verifique a conexão');
    expect(page.form.enabled).toBe(true);
    backend.expectNone('/api/users');
    page.enviar();
    await turn();
    backend.expectOne('/api/users').flush({}, { status: 201, statusText: 'Created' });
    expect(page.estado()).toBe('sucesso');
  });

  it('cancela uma inscrição pendente ao sair da tela', async () => {
    fill();
    page.enviar();
    await turn();
    const csrf = backend.expectOne('/api/auth/csrf');
    fixture.destroy();
    expect(csrf.cancelled).toBe(true);
    backend.expectNone('/api/users');
  });
});
