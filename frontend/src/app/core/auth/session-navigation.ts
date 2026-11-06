import { Injectable, effect, inject, untracked } from '@angular/core';
import { ActivatedRouteSnapshot, Router } from '@angular/router';
import { safeReturnUrl } from './return-url';
import { SessionState } from './session-state';

@Injectable({ providedIn: 'root' })
export class SessionNavigation {
  private readonly router = inject(Router);
  private readonly state = inject(SessionState);

  constructor() {
    effect(() => {
      const revision = this.state.expiredRevision();
      if (revision === null) {
        return;
      }
      untracked(() => {
        if (revision !== this.state.revision() || !this.privateRouteActive()) {
          return;
        }
        void this.router.navigate(['/entrar'], {
          queryParams: { returnUrl: safeReturnUrl(this.router.url), reason: 'expired' },
          replaceUrl: true,
        });
      });
    });
  }

  private privateRouteActive(): boolean {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    while (route) {
      if (route.data['requiresAuth'] === true) {
        return true;
      }
      route = route.firstChild;
    }
    return false;
  }
}
