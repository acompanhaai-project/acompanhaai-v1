import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
  withNoXsrfProtection,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SessionApi } from '../auth/session-api';
import { SessionState } from '../auth/session-state';
import { User } from '../auth/session.models';
import { ApiError } from './api-error';
import { apiInterceptor } from './api-interceptor';

const user: User = {
  id: 1,
  name: 'Pessoa Teste',
  email: 'pessoa@example.test',
  createdAt: '2026-11-05T12:00:00Z',
  photoUrl: null,
};
const credentials = { email: user.email, password: 'SenhaFicticia123!' };

async function turn(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

describe('Cliente de sessão e interceptor da API', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let session: SessionApi;
  let state: SessionState;
  let errors: ApiError[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withNoXsrfProtection(), withInterceptors([apiInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionApi);
    state = TestBed.inject(SessionState);
    errors = [];
  });

  afterEach(() => backend.verify());

  function token(value: string): void {
    const request = backend.expectOne('/api/auth/csrf');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBe(false);
    request.flush({ headerName: 'X-CSRF-TOKEN', token: value });
  }

  it('envia credenciais em GET da API, sem CSRF ou busca de token', () => {
    http.get('/api/users/me').subscribe();
    const request = backend.expectOne('/api/users/me');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBe(false);
    request.flush(user);
  });

  it.each([
    'https://example.test/api/events',
    '//example.test/api/events',
    '/assets/example.json',
    '/api/../assets/example.json',
  ])('não injeta CSRF ou credenciais em %s', (url) => {
    http.post(url, {}).subscribe();
    const request = backend.expectOne(url);
    expect(request.request.withCredentials).toBe(false);
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBe(false);
    request.flush({});
  });

  it('obtém CSRF antes da escrita e serializa duas operações', async () => {
    http.post('/api/applications', {}).subscribe();
    http.put('/api/applications/1', {}).subscribe();
    await turn();
    token('token-first');
    const first = backend.expectOne('/api/applications');
    expect(first.request.headers.get('X-CSRF-TOKEN')).toBe('token-first');
    backend.expectNone('/api/applications/1');
    first.flush({});
    await turn();
    const second = backend.expectOne('/api/applications/1');
    expect(second.request.headers.get('X-CSRF-TOKEN')).toBe('token-first');
    second.flush({});
  });

  it('cancelar a obtenção de CSRF libera a fila sem enviar a escrita cancelada', async () => {
    const cancelled = http.post('/api/applications', {}).subscribe();
    http.post('/api/users', {}).subscribe();
    await turn();
    const pendingToken = backend.expectOne('/api/auth/csrf');
    cancelled.unsubscribe();
    expect(pendingToken.cancelled).toBe(true);
    await turn();
    token('next-request-token');
    backend.expectNone('/api/applications');
    backend.expectOne('/api/users').flush(user, { status: 201, statusText: 'Created' });
  });

  it('não envia a escrita quando a obtenção de CSRF falha', async () => {
    http.post('/api/users', {}).subscribe({ error: (error: ApiError) => errors.push(error) });
    await turn();
    backend.expectOne('/api/auth/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    backend.expectNone('/api/users');
    expect(errors[0].status).toBe(503);
  });

  it('rejeita resposta CSRF com header fora do contrato', async () => {
    http.post('/api/users', {}).subscribe({ error: (error: ApiError) => errors.push(error) });
    await turn();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'Authorization', token: 'example' });
    backend.expectNone('/api/users');
    expect(errors[0].kind).toBe('unexpected');
  });

  it('limpa sessão e token após 401 privado, sem repetir a operação', () => {
    state.authenticate(user);
    http.get('/api/users/me').subscribe({ error: (error: ApiError) => errors.push(error) });
    backend
      .expectOne('/api/users/me')
      .flush(
        { status: 401, error: 'Autenticação necessária.' },
        { status: 401, statusText: 'Unauthorized' },
      );
    expect(state.status()).toBe('anonymous');
    expect(state.user()).toBeNull();
    expect(errors[0].kind).toBe('authentication');
    backend.expectNone('/api/users/me');
  });

  it('ignora 401 atrasado de uma requisição anterior à nova sessão', () => {
    http.get('/api/applications').subscribe({ error: (error: ApiError) => errors.push(error) });
    const old = backend.expectOne('/api/applications');
    state.authenticate(user);
    old.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(state.authenticated()).toBe(true);
    expect(state.user()).toEqual(user);
  });

  it('403 não repete POST e a próxima ação obtém outro CSRF', async () => {
    state.authenticate(user);
    http
      .post('/api/applications', {})
      .subscribe({ error: (error: ApiError) => errors.push(error) });
    await turn();
    token('old-token');
    backend
      .expectOne('/api/applications')
      .flush({ error: 'CSRF inválido.' }, { status: 403, statusText: 'Forbidden' });
    expect(errors[0].kind).toBe('forbidden');
    expect(state.authenticated()).toBe(true);
    backend.expectNone('/api/applications');

    http.post('/api/applications', {}).subscribe();
    await turn();
    token('new-token');
    const retryByUser = backend.expectOne('/api/applications');
    expect(retryByUser.request.headers.get('X-CSRF-TOKEN')).toBe('new-token');
    retryByUser.flush({});
  });

  it.each([409, 422])('preserva a sessão em %i e entrega o erro ao formulário', async (status) => {
    state.authenticate(user);
    http
      .put('/api/applications/1', {})
      .subscribe({ error: (error: ApiError) => errors.push(error) });
    await turn();
    token('example-token');
    backend
      .expectOne('/api/applications/1')
      .flush({ detail: 'Revise os dados.' }, { status, statusText: 'Rejected' });
    expect(state.user()).toEqual(user);
    expect(errors[0].message).toBe('Revise os dados.');
  });

  it('cadastro retorna a conta sem marcar a sessão como autenticada', async () => {
    const received: User[] = [];
    session
      .register({ ...credentials, name: user.name })
      .subscribe((result) => received.push(result));
    await turn();
    token('signup-token');
    backend.expectOne('/api/users').flush(user, { status: 201, statusText: 'Created' });
    expect(received).toEqual([user]);
    expect(state.authenticated()).toBe(false);
  });

  it('login renova CSRF, confirma GET me e só então libera a escrita pendente', async () => {
    session.login(credentials).subscribe();
    http.post('/api/applications', {}).subscribe();
    await turn();
    token('before-login');
    const login = backend.expectOne('/api/auth/login');
    expect(login.request.headers.get('X-CSRF-TOKEN')).toBe('before-login');
    login.flush(user);
    backend.expectNone('/api/applications');
    token('after-login');
    expect(state.status()).toBe('unknown');
    backend.expectOne('/api/users/me').flush(user);
    expect(state.user()).toEqual(user);
    await turn();
    const application = backend.expectOne('/api/applications');
    expect(application.request.headers.get('X-CSRF-TOKEN')).toBe('after-login');
    application.flush({});
  });

  it('login inválido não é repetido e não anuncia autenticação', async () => {
    session.login(credentials).subscribe({ error: (error: ApiError) => errors.push(error) });
    await turn();
    token('login-token');
    backend
      .expectOne('/api/auth/login')
      .flush({ detail: 'Credenciais inválidas.' }, { status: 401, statusText: 'Unauthorized' });
    expect(state.authenticated()).toBe(false);
    expect(errors[0].message).toBe('Credenciais inválidas.');
    backend.expectNone('/api/auth/login');
    backend.expectNone('/api/users/me');
  });

  it('falha ao renovar CSRF após login deixa estado desconhecido e não usa token antigo', async () => {
    session.login(credentials).subscribe({ error: (error: ApiError) => errors.push(error) });
    await turn();
    token('old');
    backend.expectOne('/api/auth/login').flush(user);
    backend.expectOne('/api/auth/csrf').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(state.status()).toBe('unknown');
    backend.expectNone('/api/users/me');
    expect(errors[0].status).toBe(503);
  });

  it('logout limpa o estado e prepara token para o próximo login', async () => {
    state.authenticate(user);
    session.logout().subscribe();
    await turn();
    token('logout-token');
    backend.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    expect(state.status()).toBe('anonymous');
    token('anonymous-token');
  });

  it('refresh consulta o servidor e trata 401 como sessão anônima', async () => {
    const received: (User | null)[] = [];
    session.refresh().subscribe((result) => received.push(result));
    await turn();
    backend.expectOne('/api/users/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(received).toEqual([null]);
    expect(state.status()).toBe('anonymous');
  });
});
