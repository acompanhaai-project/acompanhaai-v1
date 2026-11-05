import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of, switchMap, tap, throwError } from 'rxjs';
import { ApiError } from '../http/api-error';
import { IN_SESSION_FLOW } from '../http/api-interceptor';
import { CsrfStore } from '../http/csrf-store';
import { RequestQueue } from '../http/request-queue';
import { SessionState } from './session-state';
import { LoginRequest, RegistrationRequest, User } from './session.models';

@Injectable({ providedIn: 'root' })
export class SessionApi {
  private readonly http = inject(HttpClient);
  private readonly queue = inject(RequestQueue);
  private readonly csrf = inject(CsrfStore);
  private readonly session = inject(SessionState);

  register(request: RegistrationRequest): Observable<User> {
    return this.http.post<User>('/api/users', request);
  }

  login(request: LoginRequest): Observable<User> {
    return this.queue.exclusive(() => {
      this.session.markUnknown();
      this.csrf.clear();

      return this.http.post<User>('/api/auth/login', request, this.sessionOptions()).pipe(
        tap(() => this.csrf.clear()),
        switchMap(() => this.csrf.token()),
        switchMap(() => this.http.get<User>('/api/users/me')),
        tap((user) => this.session.authenticate(user)),
        catchError((error: unknown) => {
          this.csrf.clear();
          return throwError(() => error);
        }),
      );
    });
  }

  refresh(): Observable<User | null> {
    return this.queue.exclusive(() => {
      this.session.markUnknown();
      return this.http.get<User>('/api/users/me').pipe(
        tap((user) => this.session.authenticate(user)),
        catchError((error: unknown) => {
          if (error instanceof ApiError && error.status === 401) {
            return of(null);
          }
          return throwError(() => error);
        }),
      );
    });
  }

  logout(): Observable<void> {
    return this.queue.exclusive(() => {
      this.session.markUnknown();
      return this.http.post<void>('/api/auth/logout', null, this.sessionOptions()).pipe(
        tap(() => {
          this.session.markAnonymous();
          this.csrf.clear();
        }),
        switchMap(() => this.csrf.token()),
        map(() => undefined),
      );
    });
  }

  private sessionOptions(): { context: HttpContext } {
    return { context: new HttpContext().set(IN_SESSION_FLOW, true) };
  }
}
