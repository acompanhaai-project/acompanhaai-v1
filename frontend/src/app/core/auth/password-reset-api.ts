import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { IN_SESSION_FLOW } from '../http/api-interceptor';
import { CsrfStore } from '../http/csrf-store';
import { RequestQueue } from '../http/request-queue';
import { SessionState } from './session-state';

export interface PasswordResetRequestResponse {
  readonly message: string;
}

export interface PasswordResetConfirmation {
  readonly token: string;
  readonly newPassword: string;
}

@Injectable({ providedIn: 'root' })
export class PasswordResetApi {
  private readonly http = inject(HttpClient);
  private readonly queue = inject(RequestQueue);
  private readonly state = inject(SessionState);
  private readonly csrf = inject(CsrfStore);

  request(email: string): Observable<PasswordResetRequestResponse> {
    return this.http.post<PasswordResetRequestResponse>('/api/auth/password-reset/request', {
      email,
    });
  }

  confirm(request: PasswordResetConfirmation): Observable<void> {
    return this.queue.exclusive(() =>
      this.http
        .post<void>('/api/auth/password-reset/confirm', request, {
          context: new HttpContext().set(IN_SESSION_FLOW, true),
        })
        .pipe(
          tap(() => {
            this.state.markAnonymous();
            this.csrf.clear();
          }),
        ),
    );
  }
}
