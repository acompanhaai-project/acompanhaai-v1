import { Location } from '@angular/common';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RouterTestingHarness } from '@angular/router/testing';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { CsrfStore } from '../core/http/csrf-store';
import { RedefinirSenhaPageComponent } from './redefinir-senha-page.component';

const tokenValue = 'a'.repeat(43);
const password = ' NovaSenhaFicticia123! ';

async function turn(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

describe('Redefinição integrada à API', () => {
  let harness: RouterTestingHarness;
  let page: RedefinirSenhaPageComponent;
  let backend: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    document.body.appendChild(harness.fixture.nativeElement);
  });

  afterEach(() => {
    harness.fixture.destroy();
    harness.fixture.nativeElement.remove();
    backend.verify();
    vi.useRealTimers();
  });

  async function open(url = `/redefinir-senha#token=${tokenValue}`): Promise<void> {
    page = await harness.navigateByUrl(url, RedefinirSenhaPageComponent);
  }

  function fill(): void {
    page.form.setValue({ senha: password, confirma: password });
  }

  async function submit() {
    page.enviar();
    await turn();
    backend
      .expectOne('/api/auth/csrf')
      .flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-ficticio' });
    return backend.expectOne('/api/auth/password-reset/confirm');
  }

  it.each([
    '/redefinir-senha',
    '/redefinir-senha#token=expirado',
    `/redefinir-senha?token=${'b'.repeat(43)}#token=${tokenValue}`,
    `/redefinir-senha#token=${tokenValue}&token=${tokenValue}`,
  ])('rejeita link ausente, malformado ou ambíguo sem chamada HTTP: %s', async (url) => {
    await open(url);
    expect(page.estado()).toBe('invalido');
    page.enviar();
    backend.expectNone('/api/auth/csrf');
    backend.expectNone('/api/auth/password-reset/confirm');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Link inválido');
  });

  it.each([
    `/redefinir-senha?origem=email#token=${tokenValue}`,
    `/reset-password?token=${tokenValue}&origem=email`,
  ])('lê o token e o remove da URL, sem inventar consulta prévia: %s', async (url) => {
    await open(url);
    expect(page.estado()).toBe('ocioso');
    const location = TestBed.inject(Location).path(true);
    expect(location).not.toContain('token=');
    expect(location).not.toContain(tokenValue);
    expect(location).toContain('origem=email');
    backend.expectNone('/api/auth/password-reset/validate');
    backend.expectNone('/api/auth/csrf');
  });

  it('bloqueia senhas inválidas, longas ou diferentes antes de chamar a API', async () => {
    await open();
    page.enviar();
    expect(page.form.controls.senha.hasError('required')).toBe(true);
    page.form.setValue({ senha: password, confirma: 'Diferente123!' });
    page.enviar();
    expect(page.confirmaInvalida).toBe(true);
    page.form.setValue({ senha: password + 'a'.repeat(128), confirma: password + 'a'.repeat(128) });
    page.enviar();
    expect(page.form.controls.senha.hasError('maxlength')).toBe(true);
    backend.expectNone('/api/auth/csrf');
  });

  it('envia token e newPassword com CSRF, aguarda 204 e limpa sessão, token e senhas', async () => {
    await open();
    const state = TestBed.inject(SessionState);
    state.authenticate({
      id: 1,
      name: 'Pessoa Teste',
      email: 'conta@example.test',
      createdAt: '',
      photoUrl: null,
    });
    fill();
    const request = await submit();
    expect(request.request.body).toEqual({ token: tokenValue, newPassword: password });
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-ficticio');
    expect(page.carregando()).toBe(true);
    page.enviar();
    backend.expectNone('/api/auth/password-reset/confirm');
    request.flush(null, { status: 204, statusText: 'No Content' });
    harness.detectChanges();
    expect(page.estado()).toBe('sucesso');
    expect(page.form.getRawValue()).toEqual({ senha: '', confirma: '' });
    expect(state.status()).toBe('anonymous');
    page.enviar();
    backend.expectNone('/api/auth/password-reset/confirm');
    backend.expectNone('/api/auth/login');
    TestBed.inject(CsrfStore).token().subscribe();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'novo-csrf' });
  });

  it('trata token inválido, expirado ou usado por 400 e não repete a escrita', async () => {
    await open();
    fill();
    const request = await submit();
    request.flush(
      { detail: 'Token inválido ou expirado.' },
      { status: 400, statusText: 'Bad Request' },
    );
    harness.detectChanges();
    expect(page.estado()).toBe('invalido');
    expect(page.form.getRawValue()).toEqual({ senha: '', confirma: '' });
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain('Link inválido');
    page.enviar();
    backend.expectNone('/api/auth/password-reset/confirm');
  });

  it('associa validação 422 à nova senha e permite correção', async () => {
    await open();
    fill();
    const request = await submit();
    request.flush(
      {
        detail: 'Dados inválidos.',
        errors: [{ field: 'newPassword', message: 'Senha rejeitada.' }],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    harness.detectChanges();
    expect(page.estado()).toBe('erro');
    expect(page.form.enabled).toBe(true);
    expect(page.erroSenha()).toBe('Senha rejeitada.');
    page.form.controls.senha.setValue('OutraSenhaFicticia123!');
    expect(page.form.controls.senha.hasError('server')).toBe(false);
  });

  it('trata validação 422 do token como link inválido', async () => {
    await open();
    fill();
    const request = await submit();
    request.flush(
      { errors: [{ field: 'token', message: 'Token rejeitado.' }] },
      {
        status: 422,
        statusText: 'Unprocessable Entity',
      },
    );
    expect(page.estado()).toBe('invalido');
    expect(page.form.controls.senha.value).toBe('');
  });

  it('respeita Retry-After sem repetir automaticamente a redefinição', async () => {
    await open();
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    fill();
    const request = await submit();
    request.flush(
      {},
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '90' } },
    );
    expect(page.espera()).toBe(90);
    expect(page.relogio()).toBe('1:30');
    page.enviar();
    backend.expectNone('/api/auth/password-reset/confirm');
    vi.advanceTimersByTime(90_000);
    expect(page.espera()).toBe(0);
    backend.expectNone('/api/auth/password-reset/confirm');
    page.enviar();
    await turn();
    backend
      .expectOne('/api/auth/password-reset/confirm')
      .flush(null, { status: 204, statusText: 'No Content' });
    expect(page.estado()).toBe('sucesso');
  });

  it.each([403, 500])(
    'trata erro %i sem descartar o token para uma tentativa manual',
    async (status) => {
      await open();
      fill();
      const request = await submit();
      request.flush({}, { status, statusText: 'Rejected' });
      expect(page.estado()).toBe('erro');
      expect(page.form.enabled).toBe(true);
      expect(page.erroServidor()).not.toBe('');
      backend.expectNone('/api/auth/password-reset/confirm');
    },
  );

  it('informa incerteza após falha de rede e não afirma que a senha mudou', async () => {
    await open();
    fill();
    const request = await submit();
    request.error(new ProgressEvent('error'));
    expect(page.estado()).toBe('erro');
    expect(page.erroServidor()).toContain('Tente entrar com a nova senha');
    expect(page.form.enabled).toBe(true);
    backend.expectNone('/api/auth/password-reset/confirm');
  });

  it('cancela o fluxo pendente ao sair da página', async () => {
    await open();
    fill();
    page.enviar();
    await turn();
    const csrf = backend.expectOne('/api/auth/csrf');
    harness.fixture.destroy();
    expect(csrf.cancelled).toBe(true);
    backend.expectNone('/api/auth/password-reset/confirm');
  });

  it('aceita um novo link na mesma aba após a rejeição do anterior', async () => {
    await open();
    fill();
    const first = await submit();
    first.flush({}, { status: 400, statusText: 'Bad Request' });
    expect(page.estado()).toBe('invalido');
    const previous = page;
    const nextToken = 'b'.repeat(43);
    await open(`/redefinir-senha#token=${nextToken}`);
    expect(page).toBe(previous);
    expect(page.estado()).toBe('ocioso');
    expect(page.form.controls.senha.value).toBe('');
    expect(TestBed.inject(Location).path(true)).not.toContain('token=');
    fill();
    page.enviar();
    await turn();
    const request = backend.expectOne('/api/auth/password-reset/confirm');
    expect(request.request.body.token).toBe(nextToken);
    request.flush(null, { status: 204, statusText: 'No Content' });
    expect(page.estado()).toBe('sucesso');
  });

  it('cancela o pedido antigo ao receber outro token na mesma rota', async () => {
    await open();
    fill();
    page.enviar();
    await turn();
    const pending = backend.expectOne('/api/auth/csrf');
    await open(`/redefinir-senha#token=${'c'.repeat(43)}`);
    expect(pending.cancelled).toBe(true);
    expect(page.estado()).toBe('ocioso');
    expect(page.form.controls.senha.value).toBe('');
    backend.expectNone('/api/auth/password-reset/confirm');
  });
});
