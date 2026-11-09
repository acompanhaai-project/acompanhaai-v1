import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, defer, Observable, of, tap, throwError } from 'rxjs';
import { ApiError } from '../http/api-error';
import { EXPECTED_SESSION_REVISION } from '../http/api-interceptor';
import { SessionState } from './session-state';

const PHOTO_URL = '/api/users/me/photo';
export const MAX_PHOTO_BYTES = 2 * 1024 * 1024;

@Injectable({ providedIn: 'root' })
export class ProfilePhotoApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionState);

  load(): Observable<Blob | null> {
    return defer(() => {
      const { revision, context } = this.currentSession();
      return this.http.get(PHOTO_URL, { context, responseType: 'blob' }).pipe(
        catchError((error: unknown) => {
          if (error instanceof ApiError && error.status === 404) {
            return of(null);
          }
          return throwError(() => error);
        }),
        tap(() => this.checkRevision(revision)),
      );
    });
  }

  replace(file: File): Observable<void> {
    if (file.size === 0) {
      return throwError(() => new ApiError(422, 'validation', 'Selecione uma imagem não vazia.'));
    }
    if (file.size > MAX_PHOTO_BYTES) {
      return throwError(() => new ApiError(413, 'validation', 'A foto deve ter até 2 MiB.'));
    }

    return this.savePhoto(PHOTO_URL, (context) => {
      const body = new FormData();
      body.append('file', file);
      return this.http.put<void>(PHOTO_URL, body, { context });
    });
  }

  remove(): Observable<void> {
    return this.savePhoto(null, (context) => this.http.delete<void>(PHOTO_URL, { context }));
  }

  private savePhoto(
    photoUrl: string | null,
    send: (context: HttpContext) => Observable<void>,
  ): Observable<void> {
    return defer(() => {
      const { revision, context } = this.currentSession();
      return send(context).pipe(
        tap(() => {
          this.checkRevision(revision);
          const user = this.session.user();
          if (user) {
            this.session.updateUser({ ...user, photoUrl }, revision);
          }
        }),
      );
    });
  }

  private currentSession(): { revision: number; context: HttpContext } {
    if (!this.session.authenticated()) {
      throw new ApiError(
        401,
        'authentication',
        'Entre na sua conta para acessar a foto de perfil.',
      );
    }

    const revision = this.session.revision();
    return { revision, context: new HttpContext().set(EXPECTED_SESSION_REVISION, revision) };
  }

  private checkRevision(revision: number): void {
    if (this.session.revision() !== revision) {
      throw new ApiError(409, 'conflict', 'A sessão mudou. Reabra o perfil para consultar a foto.');
    }
  }
}
