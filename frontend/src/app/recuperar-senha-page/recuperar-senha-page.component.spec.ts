import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RouterTestingHarness } from '@angular/router/testing';
import { appConfig } from '../app.config';
import { RecuperarSenhaPageComponent } from './recuperar-senha-page.component';

async function turn(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

describe('Solicitação de recuperação integrada à API', () => {
  let harness: RouterTestingHarness;
  let page: RecuperarSenhaPageComponent;
  let backend: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    document.body.appendChild(harness.fixture.nativeElement);
    page = await harness.navigateByUrl(
      '/recuperar-senha?email=conta%40example.test',
      RecuperarSenhaPageComponent,
    );
  });

  afterEach(() => {
    harness.fixture.destroy();
    harness.fixture.nativeElement.remove();
    backend.verify();
    vi.useRealTimers();
  });

  function clock(): void {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  }

  async function submit() {
    page.enviar();
    await turn();
    const csrf = backend.expectOne('/api/auth/csrf');
    csrf.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-ficticio' });
    return backend.expectOne('/api/auth/password-reset/request');
  }

  it('preenche e-mail da rota e bloqueia entradas inválidas antes de chamar a API', () => {
    expect(page.email.value).toBe('conta@example.test');
    for (const value of ['', 'invalido', 'a'.repeat(250) + '@example.test']) {
      page.email.setValue(value);
      page.enviar();
      expect(page.mostrarErro).toBe(true);
    }
    backend.expectNone('/api/auth/csrf');
  });

  it.each(['conta@example.test', 'nao-cadastrada@example.test'])(
    'envia %s com CSRF e mostra confirmação genérica após 202',
    async (email) => {
      clock();
      page.email.setValue(`  ${email}  `);
      const request = await submit();
      expect(request.request.body).toEqual({ email });
      expect(request.request.withCredentials).toBe(true);
      expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-ficticio');
      expect(page.carregando()).toBe(true);
      page.enviar();
      backend.expectNone('/api/auth/password-reset/request');
      request.flush({ message: 'Solicitação recebida.' }, { status: 202, statusText: 'Accepted' });
      harness.detectChanges();
      expect(page.estado()).toBe('enviado');
      expect(page.email.enabled).toBe(true);
      expect(page.espera()).toBe(60);
      expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
        'Verifique seu e-mail',
      );
      expect(harness.routeNativeElement?.textContent).toContain('Se existir uma conta');
    },
  );

  it('respeita a espera de um minuto, permite reenvio manual e bloqueia alterações durante o pedido', async () => {
    clock();
    const first = await submit();
    first.flush({}, { status: 202, statusText: 'Accepted' });
    page.reenviar();
    backend.expectNone('/api/auth/password-reset/request');
    vi.advanceTimersByTime(60_000);
    expect(page.espera()).toBe(0);
    backend.expectNone('/api/auth/password-reset/request');
    page.reenviar();
    page.reenviar();
    page.usarOutroEmail();
    expect(page.estado()).toBe('enviado');
    expect(page.email.value).toBe('conta@example.test');
    await turn();
    const resend = backend.expectOne('/api/auth/password-reset/request');
    expect(resend.request.body).toEqual({ email: 'conta@example.test' });
    resend.flush({}, { status: 202, statusText: 'Accepted' });
    expect(page.reenviando()).toBe(false);
    expect(page.espera()).toBe(60);
    expect(page.aviso()).toContain('Se houver uma conta');
  });

  it('respeita Retry-After sem repetir o pedido nem liberar a cota ao trocar de e-mail', async () => {
    clock();
    const request = await submit();
    request.flush(
      {},
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '120' } },
    );
    expect(page.espera()).toBe(120);
    expect(page.relogio()).toBe('2:00');
    page.usarOutroEmail();
    page.email.setValue('outra@example.test');
    page.enviar();
    expect(page.espera()).toBe(120);
    backend.expectNone('/api/auth/password-reset/request');
    vi.advanceTimersByTime(120_000);
    expect(page.espera()).toBe(0);
    backend.expectNone('/api/auth/password-reset/request');
    page.enviar();
    await turn();
    backend
      .expectOne('/api/auth/password-reset/request')
      .flush({}, { status: 202, statusText: 'Accepted' });
    expect(page.estado()).toBe('enviado');
  });

  it('mantém erro 422 no campo após liberar o formulário', async () => {
    const request = await submit();
    request.flush(
      { detail: 'Dados inválidos.', errors: [{ field: 'email', message: 'Endereço rejeitado.' }] },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    harness.detectChanges();
    expect(page.estado()).toBe('erro');
    expect(page.email.enabled).toBe(true);
    expect(page.erroEmail()).toBe('Endereço rejeitado.');
    expect(harness.routeNativeElement?.querySelector('#erro-email')?.textContent).toContain(
      'Endereço rejeitado',
    );
    page.email.setValue('outro@example.test');
    expect(page.email.hasError('server')).toBe(false);
  });

  it.each([403, 500])(
    'trata falha %i sem confirmar envio nem repetir a escrita',
    async (status) => {
      const request = await submit();
      request.flush({}, { status, statusText: 'Rejected' });
      expect(page.estado()).toBe('erro');
      expect(page.email.enabled).toBe(true);
      expect(page.erroServidor()).not.toBe('');
      backend.expectNone('/api/auth/password-reset/request');
    },
  );

  it('não afirma que enviou o link após falha de rede', async () => {
    const request = await submit();
    request.error(new ProgressEvent('error'));
    expect(page.estado()).toBe('erro');
    expect(page.erroServidor()).toContain('Verifique a conexão');
    backend.expectNone('/api/auth/password-reset/request');
  });

  it('mostra falha de reenvio sem perder a confirmação genérica anterior', async () => {
    clock();
    const request = await submit();
    request.flush({}, { status: 202, statusText: 'Accepted' });
    vi.advanceTimersByTime(60_000);
    page.reenviar();
    await turn();
    backend.expectOne('/api/auth/password-reset/request').flush(
      {},
      {
        status: 429,
        statusText: 'Too Many Requests',
        headers: { 'Retry-After': '75' },
      },
    );
    expect(page.estado()).toBe('enviado');
    expect(page.reenviando()).toBe(false);
    expect(page.espera()).toBe(75);
    expect(page.aviso()).toContain('Limite');
  });

  it('cancela o pedido pendente ao sair da tela', async () => {
    page.enviar();
    await turn();
    const csrf = backend.expectOne('/api/auth/csrf');
    harness.fixture.destroy();
    expect(csrf.cancelled).toBe(true);
    backend.expectNone('/api/auth/password-reset/request');
  });
});
