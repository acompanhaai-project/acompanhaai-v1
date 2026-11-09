import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, defer, map, Observable, of, switchMap, tap, throwError } from 'rxjs';
import { ApiError } from '../http/api-error';
import { EXPECTED_SESSION_REVISION, IN_SESSION_FLOW } from '../http/api-interceptor';
import { CsrfStore } from '../http/csrf-store';
import { RequestQueue } from '../http/request-queue';
import { SessionState } from './session-state';
import {
  LoginRequest,
  PasswordChangeRequest,
  ProfileUpdateRequest,
  RegistrationRequest,
  User,
} from './session.models';

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
        switchMap(() => this.http.get<User>('/api/users/me', this.sessionOptions())),
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
      return this.http.get<User>('/api/users/me', this.sessionOptions()).pipe(
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

  updateProfile({ name, email }: ProfileUpdateRequest): Observable<User> {
    return defer(() => {
      if (!this.session.authenticated()) {
        return throwError(
          () => new ApiError(401, 'authentication', 'Entre na sua conta para editar o perfil.'),
        );
      }

      const revision = this.session.revision();
      const context = new HttpContext().set(EXPECTED_SESSION_REVISION, revision);

      return this.http.put<User>('/api/users/me', { name, email }, { context }).pipe(
        tap((user) => {
          if (!this.session.updateUser(user, revision)) {
            throw new ApiError(
              409,
              'conflict',
              'A sessão mudou durante a edição. Recarregue o perfil para conferir os dados.',
            );
          }
        }),
      );
    });
  }

  changePassword({ currentPassword, newPassword }: PasswordChangeRequest): Observable<void> {
    return defer(() => {
      if (!this.session.authenticated()) {
        return throwError(
          () => new ApiError(401, 'authentication', 'Entre na sua conta para trocar a senha.'),
        );
      }

      const revision = this.session.revision();
      const context = new HttpContext().set(EXPECTED_SESSION_REVISION, revision);

      return this.http
        .put<void>('/api/users/me/password', { currentPassword, newPassword }, { context })
        .pipe(tap(() => this.clearSessionAtRevision(revision)));
    });
  }

  deleteAccount(confirmedEmail: string): Observable<void> {
    return defer(() => {
      const user = this.session.user();
      if (!user) {
        return throwError(
          () =>
            new ApiError(401, 'authentication', 'Entre na sua conta para confirmar a exclusão.'),
        );
      }
      if (confirmedEmail.trim().toLowerCase() !== user.email.toLowerCase()) {
        return throwError(
          () =>
            new ApiError(
              422,
              'validation',
              'Digite o e-mail da sua conta para confirmar a exclusão.',
            ),
        );
      }

      const revision = this.session.revision();
      const context = new HttpContext().set(EXPECTED_SESSION_REVISION, revision);

      return this.http
        .delete<void>('/api/users/me', {
          context,
          headers: { 'X-Expected-Account-Id': String(user.id) },
        })
        .pipe(tap(() => this.clearSessionAtRevision(revision)));
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

  private clearSessionAtRevision(revision: number): void {
    if (this.session.revision() === revision) {
      this.session.markAnonymous();
      this.csrf.clear();
    }
  }

  private sessionOptions(): { context: HttpContext } {
    return { context: new HttpContext().set(IN_SESSION_FLOW, true) };
  }
}
