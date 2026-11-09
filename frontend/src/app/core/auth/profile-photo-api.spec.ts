import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { appConfig } from '../../app.config';
import { ProfilePhotoApi } from './profile-photo-api';
import { SessionState } from './session-state';

const photoUrl = '/api/users/me/photo';
const user = {
  id: 1,
  name: 'Pessoa Teste',
  email: 'conta@example.test',
  createdAt: '2026-11-05T12:00:00Z',
  photoUrl: null,
};

describe('Foto privada de perfil', () => {
  let api: ProfilePhotoApi;
  let state: SessionState;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    api = TestBed.inject(ProfilePhotoApi);
    state = TestBed.inject(SessionState);
    backend = TestBed.inject(HttpTestingController);
    state.authenticate(user);
  });

  afterEach(() => backend.verify());

  it('envia multipart com CSRF, recarrega a imagem e remove a foto após confirmação', async () => {
    const file = new File(['imagem de teste'], 'foto.png', { type: 'image/png' });
    api.replace(file).subscribe();
    await Promise.resolve();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'photo-token' });
    const upload = backend.expectOne({ method: 'PUT', url: photoUrl });
    expect(upload.request.body).toBeInstanceOf(FormData);
    expect(upload.request.body.get('file')).toBe(file);
    expect(upload.request.headers.has('Content-Type')).toBe(false);
    expect(upload.request.headers.get('X-CSRF-TOKEN')).toBe('photo-token');
    expect(state.user()?.photoUrl).toBeNull();
    upload.flush(null, { status: 204, statusText: 'No Content' });
    expect(state.user()?.photoUrl).toBe(photoUrl);

    const loaded = vi.fn();
    api.load().subscribe(loaded);
    const download = backend.expectOne({ method: 'GET', url: photoUrl });
    expect(download.request.withCredentials).toBe(true);
    const photo = new Blob(['imagem salva'], { type: 'image/png' });
    download.flush(photo);
    expect(loaded).toHaveBeenCalledWith(photo);

    api.remove().subscribe();
    await Promise.resolve();
    const removal = backend.expectOne({ method: 'DELETE', url: photoUrl });
    expect(removal.request.headers.get('X-CSRF-TOKEN')).toBe('photo-token');
    expect(state.user()?.photoUrl).toBe(photoUrl);
    removal.flush(null, { status: 204, statusText: 'No Content' });
    expect(state.user()?.photoUrl).toBeNull();
  });

  it('representa a ausência de foto com null sem expirar a sessão', () => {
    const received = vi.fn();
    api.load().subscribe(received);
    backend.expectOne({ method: 'GET', url: photoUrl }).flush(new Blob(), {
      status: 404,
      statusText: 'Not Found',
    });
    expect(received).toHaveBeenCalledWith(null);
    expect(state.authenticated()).toBe(true);
  });

  it('preserva a foto existente se a API recusar a imagem enviada', async () => {
    state.authenticate({ ...user, photoUrl });
    const failed = vi.fn();
    api.replace(new File(['conteúdo inválido'], 'foto.png')).subscribe({ error: failed });
    await Promise.resolve();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'photo-token' });
    backend
      .expectOne({ method: 'PUT', url: photoUrl })
      .flush(
        { detail: 'A foto deve ser JPEG ou PNG.' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
    expect(failed).toHaveBeenCalledWith(expect.objectContaining({ status: 422 }));
    expect(state.user()?.photoUrl).toBe(photoUrl);
  });

  it('não envia arquivos acima de 2 MiB', () => {
    const failed = vi.fn();
    const file = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'grande.png');
    api.replace(file).subscribe({ error: failed });
    expect(failed).toHaveBeenCalledWith(expect.objectContaining({ status: 413 }));
    backend.expectNone(photoUrl);
    backend.expectNone('/api/auth/csrf');
  });
});
