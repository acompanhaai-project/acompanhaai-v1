import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { appConfig } from '../../app.config';
import { SessionAccess } from './session-access';
import { SessionState } from './session-state';

async function turn(): Promise<void> {
  for (let index = 0; index < 10; index += 1) {
    await Promise.resolve();
  }
}

describe('Verificação da sessão no servidor', () => {
  let access: SessionAccess;
  let state: SessionState;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    access = TestBed.inject(SessionAccess);
    state = TestBed.inject(SessionState);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  it('compartilha a consulta pendente e restaura a identidade recebida da API', async () => {
    const first = vi.fn();
    const second = vi.fn();
    access.check().subscribe(first);
    access.check().subscribe(second);
    await turn();
    expect(access.checking()).toBe(true);
    const user = {
      id: 1,
      name: 'Pessoa Teste',
      email: 'conta@example.test',
      createdAt: '',
      photoUrl: null,
    };
    const request = backend.expectOne('/api/users/me');
    expect(request.request.withCredentials).toBe(true);
    request.flush(user);
    expect(first).toHaveBeenCalledWith(user);
    expect(second).toHaveBeenCalledWith(user);
    expect(state.authenticated()).toBe(true);
    expect(access.checking()).toBe(false);
    expect(access.error()).toBeNull();
  });

  it('trata somente 401 como ausência de sessão e não como falha de conexão', async () => {
    const received = vi.fn();
    access.check().subscribe(received);
    await turn();
    backend.expectOne('/api/users/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(received).toHaveBeenCalledWith(null);
    expect(state.status()).toBe('anonymous');
    expect(access.error()).toBeNull();
    expect(state.expiredRevision()).toBeNull();
  });

  it('mantém estado desconhecido em 503 e permite recuperação por tentativa manual', async () => {
    const failed = vi.fn();
    access.check().subscribe({ error: failed });
    await turn();
    backend.expectOne('/api/users/me').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(failed).toHaveBeenCalledOnce();
    expect(state.status()).toBe('unknown');
    expect(access.error()?.status).toBe(503);
    access.check().subscribe();
    await turn();
    expect(access.error()).toBeNull();
    backend.expectOne('/api/users/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(state.status()).toBe('anonymous');
  });

  it('limita a espera a dez segundos sem assumir que o usuário está deslogado', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    const failed = vi.fn();
    access.check().subscribe({ error: failed });
    await turn();
    const request = backend.expectOne('/api/users/me');
    vi.advanceTimersByTime(10_001);
    expect(request.cancelled).toBe(true);
    expect(failed).toHaveBeenCalledOnce();
    expect(state.status()).toBe('unknown');
    expect(access.checking()).toBe(false);
    expect(access.error()).not.toBeNull();
  });

  it('cancela a consulta quando não há mais interessados', async () => {
    const subscription = access.check().subscribe();
    await turn();
    const request = backend.expectOne('/api/users/me');
    subscription.unsubscribe();
    expect(request.cancelled).toBe(true);
    expect(access.checking()).toBe(false);
  });
});
