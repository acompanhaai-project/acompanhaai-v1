import { HttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { appConfig } from '../../app.config';
import { AuthPageComponent } from '../../auth-page/auth-page.component';
import { safeReturnUrl } from './return-url';
import { SessionNavigation } from './session-navigation';
import { SessionState } from './session-state';

const user = {
  id: 1,
  name: 'Pessoa Teste',
  email: 'conta@example.test',
  createdAt: '',
  photoUrl: null,
};

describe('Destino interno após login', () => {
  it.each([
    null,
    '',
    'https://example.test',
    '//example.test',
    '/\\example.test',
    '/entrar',
    '/cadastro',
    '/conta/../entrar',
    '/conta(aux:entrar)',
    '/conta;destino=externo',
    '/%2f%2fexample.test',
    '/conta?x=\n',
  ])('rejeita destino externo, inválido ou em loop: %s', (value) => {
    expect(safeReturnUrl(value)).toBe('/conta');
  });

  it('preserva query e fragmento de um destino privado permitido', () => {
    expect(safeReturnUrl('/conta?aba=perfil#dados')).toBe('/conta?aba=perfil#dados');
  });
});

describe('Rotas privadas e restauração da sessão', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let router: Router;
  let state: SessionState;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    state = TestBed.inject(SessionState);
    TestBed.inject(SessionNavigation);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    harness.fixture.destroy();
    backend.verify();
  });

  async function me(): Promise<TestRequest> {
    let request: TestRequest | undefined;
    await vi.waitFor(() => {
      request = backend.expectOne('/api/users/me');
    });
    return request!;
  }

  it('não renderiza a conta antes de confirmar a sessão existente no servidor', async () => {
    const navigation = harness.navigateByUrl('/conta?aba=perfil#dados');
    const request = await me();
    expect(harness.routeNativeElement?.textContent ?? '').not.toContain('Acesso confirmado');
    request.flush(user);
    await navigation;
    expect(router.url).toBe('/conta?aba=perfil#dados');
    expect(state.user()).toEqual(user);
    expect(harness.routeNativeElement?.textContent).toContain('Acesso confirmado');
  });

  it('redireciona 401 para login preservando o destino, sem duplicar a consulta', async () => {
    const navigation = harness.navigateByUrl('/conta?aba=perfil#dados');
    (await me()).flush({}, { status: 401, statusText: 'Unauthorized' });
    await navigation;
    const url = router.parseUrl(router.url);
    expect(url.root.children['primary'].segments[0].path).toBe('entrar');
    expect(url.queryParams['returnUrl']).toBe('/conta?aba=perfil#dados');
    expect(harness.routeNativeElement?.textContent).not.toContain('Acesso confirmado');
    backend.expectNone('/api/users/me');
  });

  it('consulta a sessão ao abrir login e envia uma sessão válida para a conta protegida', async () => {
    const navigation = harness.navigateByUrl('/entrar?returnUrl=%2Fconta%3Faba%3Dperfil');
    (await me()).flush(user);
    (await me()).flush(user);
    await navigation;
    expect(router.url).toBe('/conta?aba=perfil');
    expect(state.authenticated()).toBe(true);
  });

  it('mantém o login público após 401 sem persistir identidade ou credenciais', async () => {
    const navigation = harness.navigateByUrl('/entrar');
    (await me()).flush({}, { status: 401, statusText: 'Unauthorized' });
    await navigation;
    expect(router.url).toBe('/entrar');
    expect(harness.routeNativeElement?.querySelector('#email')).not.toBeNull();
    expect(state.user()).toBeNull();
  });

  it('mostra erro com tentativa manual em 503, sem tratar a falha como usuário anônimo', async () => {
    const navigation = harness.navigateByUrl('/conta');
    (await me()).flush({}, { status: 503, statusText: 'Unavailable' });
    await navigation;
    expect(state.status()).toBe('unknown');
    expect(harness.routeNativeElement?.textContent).toContain(
      'Não foi possível verificar sua sessão',
    );
    expect(harness.routeNativeElement?.querySelector('#email')).toBeNull();
    const page = harness.routeDebugElement?.componentInstance as AuthPageComponent;
    page.verificarNovamente();
    (await me()).flush(user);
    (await me()).flush(user);
    await vi.waitFor(() => expect(router.url).toBe('/conta'));
    expect(state.authenticated()).toBe(true);
  });

  it('retorna ao destino protegido após autenticar e não permite redirecionamento externo', async () => {
    state.markAnonymous();
    await harness.navigateByUrl('/entrar?returnUrl=https%3A%2F%2Fexample.test');
    const page = harness.routeDebugElement?.componentInstance as AuthPageComponent;
    page.email.setValue(user.email);
    page.onSubmit();
    page.senha.setValue('SenhaFicticia123!');
    page.onSubmit();
    let csrf: TestRequest | undefined;
    await vi.waitFor(() => {
      csrf = backend.expectOne('/api/auth/csrf');
    });
    csrf!.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-anonimo' });
    backend.expectOne('/api/auth/login').flush(user);
    backend
      .expectOne('/api/auth/csrf')
      .flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-autenticado' });
    backend.expectOne('/api/users/me').flush(user);
    (await me()).flush(user);
    await vi.waitFor(() => expect(router.url).toBe('/conta'));
    expect(state.authenticated()).toBe(true);
  });

  it('redireciona um 401 privado para login e informa expiração sem repetir a operação', async () => {
    const navigation = harness.navigateByUrl('/conta?aba=perfil');
    (await me()).flush(user);
    await navigation;
    TestBed.inject(HttpClient)
      .get('/api/applications')
      .subscribe({ error: () => undefined });
    backend.expectOne('/api/applications').flush({}, { status: 401, statusText: 'Unauthorized' });
    TestBed.tick();
    await harness.fixture.whenStable();
    expect(router.url).toContain('/entrar?');
    expect(router.parseUrl(router.url).queryParams['returnUrl']).toBe('/conta?aba=perfil');
    expect(harness.routeNativeElement?.textContent).toContain('Sua sessão expirou');
    backend.expectNone('/api/applications');
  });

  it('não redireciona a conta por 403 nem por 401 atrasado de uma sessão anterior', async () => {
    const navigation = harness.navigateByUrl('/conta');
    (await me()).flush(user);
    await navigation;
    const http = TestBed.inject(HttpClient);
    http.get('/api/applications').subscribe({ error: () => undefined });
    backend.expectOne('/api/applications').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(state.authenticated()).toBe(true);
    http.get('/api/applications').subscribe({ error: () => undefined });
    const old = backend.expectOne('/api/applications');
    state.authenticate(user);
    old.flush({}, { status: 401, statusText: 'Unauthorized' });
    TestBed.tick();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/conta');
    expect(state.authenticated()).toBe(true);
  });
});
