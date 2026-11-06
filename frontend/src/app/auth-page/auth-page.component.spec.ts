import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { AuthPageComponent } from './auth-page.component';

const user = {
  id: 1,
  name: 'Maria da Silva',
  email: 'maria@example.test',
  createdAt: '2026-11-06T12:00:00Z',
  photoUrl: null,
};
const password = 'SenhaFicticia123!';

async function turn(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

describe('Login integrado à sessão e CSRF', () => {
  let fixture: ComponentFixture<AuthPageComponent>;
  let page: AuthPageComponent;
  let backend: HttpTestingController;
  let state: SessionState;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(AuthPageComponent);
    page = fixture.componentInstance;
    backend = TestBed.inject(HttpTestingController);
    state = TestBed.inject(SessionState);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    fixture.nativeElement.remove();
    backend.verify();
    vi.restoreAllMocks();
  });

  function token(value: string): void {
    const request = backend.expectOne('/api/auth/csrf');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ headerName: 'X-CSRF-TOKEN', token: value });
  }

  async function login() {
    page.email.setValue('  maria@example.test  ');
    page.onSubmit();
    fixture.detectChanges();
    page.senha.setValue(password);
    page.onSubmit();
    await turn();
    token('csrf-anonimo');
    return backend.expectOne('/api/auth/login');
  }

  async function authenticate(): Promise<void> {
    const request = await login();
    request.flush(user);
    token('csrf-autenticado');
    backend.expectOne('/api/users/me').flush(user);
    fixture.detectChanges();
  }

  it('valida o e-mail e avança para senha sem enviar código ou consultar a API', () => {
    page.email.setValue('invalido');
    page.onSubmit();
    fixture.detectChanges();
    expect(page.etapa()).toBe('email');
    expect(page.mostrarErro).toBe(true);
    page.email.setValue('  maria@example.test  ');
    page.onSubmit();
    fixture.detectChanges();
    expect(page.etapa()).toBe('senha');
    expect(page.email.value).toBe(user.email);
    expect(
      fixture.nativeElement.querySelector('input[autocomplete="current-password"]'),
    ).not.toBeNull();
    expect(page.mensagem()).toBe('');
    backend.expectNone('/api/auth/login');
    backend.expectNone('/api/auth/csrf');
  });

  it('aguarda login, renovação de CSRF e GET me antes de confirmar o acesso', async () => {
    const local = vi.spyOn(localStorage, 'setItem');
    const session = vi.spyOn(sessionStorage, 'setItem');
    const log = vi.spyOn(console, 'log');
    const request = await login();
    expect(request.request.body).toEqual({ email: user.email, password });
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-anonimo');
    page.onSubmit();
    backend.expectNone('/api/auth/login');
    expect(page.carregando()).toBe(true);
    expect(page.form.disabled).toBe(true);
    request.flush(user);
    expect(state.authenticated()).toBe(false);
    backend.expectNone('/api/users/me');
    token('csrf-renovado');
    expect(state.authenticated()).toBe(false);
    backend.expectOne('/api/users/me').flush(user);
    fixture.detectChanges();
    expect(state.authenticated()).toBe(true);
    expect(page.senha.value).toBe('');
    expect(page.carregando()).toBe(false);
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Acesso confirmado');
    expect(local).not.toHaveBeenCalled();
    expect(session).not.toHaveBeenCalled();
    expect(log).not.toHaveBeenCalled();
  });

  it('não aplica a política de criação de senha ao login e preserva espaços da senha enviada', async () => {
    page.email.setValue(user.email);
    page.onSubmit();
    page.onSubmit();
    expect(page.senha.hasError('required')).toBe(true);
    backend.expectNone('/api/auth/csrf');
    page.senha.setValue('x'.repeat(129));
    page.onSubmit();
    expect(page.senha.hasError('maxlength')).toBe(true);
    backend.expectNone('/api/auth/login');
    page.senha.setValue(' legado ');
    expect(page.senha.valid).toBe(true);
    page.onSubmit();
    await turn();
    token('csrf');
    const request = backend.expectOne('/api/auth/login');
    expect(request.request.body.password).toBe(' legado ');
    request.flush({}, { status: 401, statusText: 'Unauthorized' });
  });

  it('exibe mensagem genérica para 401, limpa a senha e permite tentativa manual com outro CSRF', async () => {
    const request = await login();
    request.flush({ detail: 'Conta inexistente.' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    expect(state.authenticated()).toBe(false);
    expect(page.erroServidor()).toBe('E-mail ou senha inválidos.');
    expect(page.senha.value).toBe('');
    expect(page.form.enabled).toBe(true);
    backend.expectNone('/api/auth/login');
    backend.expectNone('/api/users/me');
    page.senha.setValue(password);
    page.onSubmit();
    await turn();
    token('novo-csrf');
    backend.expectOne('/api/auth/login').flush(user);
    token('csrf-renovado');
    backend.expectOne('/api/users/me').flush(user);
    expect(state.authenticated()).toBe(true);
  });

  it.each([403, 429, 500])(
    'não repete login após falha %i e libera o formulário',
    async (status) => {
      const request = await login();
      request.flush({}, { status, statusText: 'Rejected' });
      fixture.detectChanges();
      expect(state.authenticated()).toBe(false);
      expect(page.form.enabled).toBe(true);
      expect(page.senha.value).toBe('');
      expect(page.erroServidor()).not.toBe('');
      expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
      backend.expectNone('/api/auth/login');
      backend.expectNone('/api/users/me');
    },
  );

  it('trata falha de rede sem anunciar acesso confirmado', async () => {
    const request = await login();
    request.error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(state.authenticated()).toBe(false);
    expect(page.erroServidor()).toContain('Verifique a conexão');
    expect(page.carregando()).toBe(false);
    expect(page.senha.value).toBe('');
    backend.expectNone('/api/auth/login');
  });

  it('não confirma o acesso quando a renovação de CSRF falha após o login', async () => {
    const request = await login();
    request.flush(user);
    backend.expectOne('/api/auth/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(state.status()).toBe('unknown');
    expect(page.form.enabled).toBe(true);
    expect(page.senha.value).toBe('');
    expect(page.erroServidor()).not.toBe('');
    backend.expectNone('/api/users/me');
  });

  it('não confirma o acesso quando GET me recusa a sessão', async () => {
    const request = await login();
    request.flush(user);
    token('csrf-renovado');
    backend.expectOne('/api/users/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(state.status()).toBe('anonymous');
    expect(page.senha.value).toBe('');
    expect(page.erroServidor()).not.toBe('');
    expect(page.form.enabled).toBe(true);
  });

  it('apresenta validação 422 no e-mail e limpa a senha', async () => {
    const request = await login();
    request.flush(
      { detail: 'Dados inválidos.', errors: [{ field: 'email', message: 'E-mail rejeitado.' }] },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    fixture.detectChanges();
    expect(page.etapa()).toBe('email');
    expect(page.erroEmail()).toBe('E-mail rejeitado.');
    expect(page.senha.value).toBe('');
    expect(fixture.nativeElement.querySelector('#erro-email').textContent).toContain(
      'E-mail rejeitado',
    );
  });

  it('altera o e-mail e limpa a senha sem chamar a API', () => {
    page.email.setValue(user.email);
    page.onSubmit();
    page.senha.setValue(password);
    page.verSenha.set(true);
    page.alterarEmail();
    expect(page.etapa()).toBe('email');
    expect(page.senha.value).toBe('');
    expect(page.verSenha()).toBe(false);
    expect(page.email.value).toBe(user.email);
    backend.expectNone('/api/auth/login');
  });

  it('encerra a sessão uma única vez e prepara CSRF anônimo para o próximo acesso', async () => {
    await authenticate();
    page.sair();
    page.sair();
    await turn();
    const logout = backend.expectOne('/api/auth/logout');
    expect(logout.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-autenticado');
    expect(page.saindo()).toBe(true);
    logout.flush(null, { status: 204, statusText: 'No Content' });
    expect(state.status()).toBe('anonymous');
    token('csrf-anonimo');
    fixture.detectChanges();
    expect(page.mensagem()).toBe('Sessão encerrada.');
    expect(page.etapa()).toBe('email');
    expect(page.email.value).toBe('');
    expect(page.senha.value).toBe('');
    expect(page.saindo()).toBe(false);
    backend.expectNone('/api/auth/logout');
  });

  it('não anuncia saída concluída se o pedido de logout falhar', async () => {
    await authenticate();
    page.sair();
    await turn();
    backend.expectOne('/api/auth/logout').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(state.status()).toBe('unknown');
    expect(page.mensagem()).toBe('');
    expect(page.erroServidor()).toContain('Não foi possível confirmar o encerramento');
    expect(page.form.enabled).toBe(true);
    backend.expectNone('/api/auth/logout');
  });

  it('mantém a saída confirmada se somente a obtenção do CSRF anônimo falhar após 204', async () => {
    await authenticate();
    page.sair();
    await turn();
    backend.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    backend.expectOne('/api/auth/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(state.status()).toBe('anonymous');
    expect(page.mensagem()).toContain('Sessão encerrada.');
    expect(page.form.enabled).toBe(true);
    expect(page.saindo()).toBe(false);
  });

  it('cancela o fluxo ao destruir a tela e não envia o login pendente', async () => {
    page.email.setValue(user.email);
    page.onSubmit();
    page.senha.setValue(password);
    page.onSubmit();
    await turn();
    const csrf = backend.expectOne('/api/auth/csrf');
    fixture.destroy();
    expect(csrf.cancelled).toBe(true);
    backend.expectNone('/api/auth/login');
  });
});
