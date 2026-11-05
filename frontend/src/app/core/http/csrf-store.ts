import { HttpBackend, HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, finalize, map, Observable, of, shareReplay, tap, throwError } from 'rxjs';
import { CsrfToken } from '../auth/session.models';
import { ApiError, toApiError } from './api-error';

@Injectable({ providedIn: 'root' })
export class CsrfStore {
  private readonly http = new HttpClient(inject(HttpBackend));
  private current: CsrfToken | null = null;
  private pending: Observable<CsrfToken> | undefined;

  token(): Observable<CsrfToken> {
    if (this.current) {
      return of(this.current);
    }
    if (this.pending) {
      return this.pending;
    }

    this.pending = this.http.get<CsrfToken>('/api/auth/csrf', { withCredentials: true }).pipe(
      map((response) => {
        if (
          response?.headerName !== 'X-CSRF-TOKEN' ||
          typeof response.token !== 'string' ||
          !response.token.trim() ||
          /[\r\n]/.test(response.token)
        ) {
          throw new ApiError(0, 'unexpected', 'Não foi possível preparar a proteção da sessão.');
        }
        return { headerName: response.headerName, token: response.token };
      }),
      tap((token) => {
        this.current = token;
      }),
      catchError((error: unknown) => throwError(() => toApiError(error))),
      finalize(() => {
        this.pending = undefined;
      }),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
    return this.pending;
  }

  clear(): void {
    this.current = null;
  }
}
