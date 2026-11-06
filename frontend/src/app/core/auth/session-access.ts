import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, defer, finalize, shareReplay, throwError, timeout } from 'rxjs';
import { ApiError, toApiError } from '../http/api-error';
import { SessionApi } from './session-api';
import { SessionState } from './session-state';
import { User } from './session.models';

@Injectable({ providedIn: 'root' })
export class SessionAccess {
  private readonly api = inject(SessionApi);
  private readonly state = inject(SessionState);
  private readonly currentError = signal<ApiError | null>(null);
  private readonly currentChecking = signal(false);
  private pending: Observable<User | null> | undefined;

  readonly error = computed(() => (this.state.status() === 'unknown' ? this.currentError() : null));
  readonly checking = this.currentChecking.asReadonly();

  check(): Observable<User | null> {
    if (this.pending) {
      return this.pending;
    }

    this.pending = defer(() => {
      this.currentError.set(null);
      this.currentChecking.set(true);
      return this.api.refresh();
    }).pipe(
      timeout({ first: 10_000 }),
      catchError((cause: unknown) => {
        if (this.state.status() !== 'unknown') {
          this.state.markUnknown();
        }
        const error = toApiError(cause);
        this.currentError.set(error);
        return throwError(() => error);
      }),
      finalize(() => {
        this.currentChecking.set(false);
        this.pending = undefined;
      }),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
    return this.pending;
  }
}
