import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { safeReturnUrl } from './return-url';
import { SessionAccess } from './session-access';
import { SessionState } from './session-state';

export const requireSession: CanActivateFn = (_route, destination) => {
  const access = inject(SessionAccess);
  const state = inject(SessionState);
  const router = inject(Router);
  const wasAuthenticated = state.authenticated();
  const login = () =>
    router.createUrlTree(['/entrar'], {
      queryParams: {
        returnUrl: safeReturnUrl(destination.url),
        ...(wasAuthenticated ? { reason: 'expired' } : {}),
      },
    });

  return access.check().pipe(
    map((user) => (user ? true : login())),
    catchError(() => of(login())),
  );
};

export const restoreSessionOnLogin: CanActivateFn = (route) => {
  const access = inject(SessionAccess);
  const state = inject(SessionState);
  const router = inject(Router);
  const destination = () => router.parseUrl(safeReturnUrl(route.queryParamMap.get('returnUrl')));

  if (state.authenticated()) {
    return destination();
  }
  if (state.status() === 'anonymous' || access.error()) {
    return true;
  }

  return access.check().pipe(
    map((user) => (user ? destination() : true)),
    catchError(() => of(true)),
  );
};
