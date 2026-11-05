import { Component, input } from '@angular/core';

@Component({
  selector: 'app-loading-state',
  template: `
    <div class="loading-state" role="status" aria-live="polite" aria-atomic="true">
      <span class="loading-label">{{ message() }}</span>
      <div class="skeleton" aria-hidden="true"></div>
      <div class="skeleton short" aria-hidden="true"></div>
    </div>
  `,
  styles: `
    .loading-state {
      padding: var(--space-5);
    }

    .loading-label {
      display: block;
      margin-bottom: var(--space-5);
      color: var(--color-muted);
    }

    .skeleton {
      width: 100%;
      height: 16px;
      margin-top: var(--space-3);
      border-radius: var(--radius-control);
      background: var(--color-primary-soft);
    }

    .short {
      width: 65%;
    }
  `,
})
export class LoadingState {
  readonly message = input('Carregando…');
}
