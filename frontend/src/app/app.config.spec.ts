import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';

describe('Infraestrutura HTTP na configuração da aplicação', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('preserva o envio de credenciais pelo interceptor configurado no bootstrap', () => {
    http.get('/api/users/me').subscribe();
    const request = backend.expectOne('/api/users/me');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBe(false);
    request.flush({});
  });

  it('prepara o CSRF antes de uma escrita usando os providers reais da aplicação', async () => {
    http.post('/api/users', {}).subscribe();
    for (let index = 0; index < 5; index += 1) {
      await Promise.resolve();
    }

    backend.expectNone('/api/users');
    const csrf = backend.expectOne('/api/auth/csrf');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush({ headerName: 'X-CSRF-TOKEN', token: 'csrf-ficticio-de-teste' });

    const registration = backend.expectOne('/api/users');
    expect(registration.request.withCredentials).toBe(true);
    expect(registration.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-ficticio-de-teste');
    registration.flush({});
  });
});
