import { Component, input } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty-state">
      <svg viewBox="0 0 48 48" width="48" height="48" fill="none" aria-hidden="true">
        <rect x="9" y="7" width="30" height="34" rx="5" stroke="currentColor" stroke-width="2" />
        <path
          d="M17 18h14M17 25h14M17 32h8"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        />
      </svg>
      <h2>{{ title() }}</h2>
      <p>{{ message() }}</p>
      <ng-content />
    </div>
  `,
  styles: `
    .empty-state {
      display: grid;
      justify-items: center;
      text-align: center;
      padding: var(--space-6);
    }

    svg {
      color: var(--color-primary);
      margin-bottom: var(--space-5);
    }

    h2 {
      margin-bottom: var(--space-3);
      font-size: 1.25rem;
    }

    p {
      max-width: 48ch;
      color: var(--color-muted);
      margin-bottom: var(--space-5);
    }
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly message = input.required<string>();
}
