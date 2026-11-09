import { DOCUMENT } from '@angular/common';
import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { SessionState } from '../auth/session-state';
import { ApiError, toApiError } from './api-error';
import { CsrfStore } from './csrf-store';
import { RequestQueue } from './request-queue';

export const IN_SESSION_FLOW = new HttpContextToken(() => false);
export const EXPECTED_SESSION_REVISION = new HttpContextToken<number | null>(() => null);

export const apiInterceptor: HttpInterceptorFn = (request, next) => {
  const document = inject(DOCUMENT);
  const base = new URL(document.baseURI);
  const url = new URL(request.url, base);

  if (
    url.origin !== base.origin ||
    (url.pathname !== '/api' && !url.pathname.startsWith('/api/'))
  ) {
    return next(request);
  }

  const csrf = inject(CsrfStore);
  const session = inject(SessionState);
  const queue = inject(RequestQueue);
  const writing = !['GET', 'HEAD', 'OPTIONS'].includes(request.method);
  const anonymousRoute =
    (request.method === 'GET' && url.pathname === '/api/auth/csrf') ||
    (request.method === 'POST' &&
      [
        '/api/users',
        '/api/auth/login',
        '/api/auth/password-reset/request',
        '/api/auth/password-reset/confirm',
      ].includes(url.pathname));
  const operation = () => {
    const revision = session.revision();
    const expectedRevision = request.context.get(EXPECTED_SESSION_REVISION);
    if (expectedRevision !== null && expectedRevision !== revision) {
      return throwError(
        () => new ApiError(409, 'conflict', 'A sessão mudou. Recarregue os dados antes de editar.'),
      );
    }

    const credentialed = request.clone({ withCredentials: true });
    const response = writing
      ? csrf
          .token()
          .pipe(
            switchMap((token) =>
              next(credentialed.clone({ setHeaders: { [token.headerName]: token.token } })),
            ),
          )
      : next(credentialed);

    return response.pipe(
      catchError((cause: unknown) => {
        const error = toApiError(cause);
        const incorrectCurrentPassword =
          request.method === 'PUT' &&
          url.pathname === '/api/users/me/password' &&
          error.code === 'INCORRECT_CURRENT_PASSWORD';
        if (revision === session.revision()) {
          if (error.status === 401 && !anonymousRoute && !incorrectCurrentPassword) {
            if (request.context.get(IN_SESSION_FLOW)) {
              session.markAnonymous();
            } else {
              session.markExpired();
            }
            csrf.clear();
          } else if (error.status === 403) {
            csrf.clear();
          }
        }
        return throwError(() => error);
      }),
    );
  };

  return writing && !request.context.get(IN_SESSION_FLOW)
    ? queue.exclusive(operation)
    : operation();
};
