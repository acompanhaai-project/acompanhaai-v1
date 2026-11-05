import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-error-state',
  template: `
    <div class="error-state" role="alert">
      <h2>{{ title() }}</h2>
      <p>{{ message() }}</p>
      @if (retryLabel()) {
        <button type="button" class="secondary" (click)="retry.emit()">{{ retryLabel() }}</button>
      }
    </div>
  `,
  styles: `
    .error-state {
      padding: var(--space-5);
      border-radius: var(--radius-panel);
      background: var(--color-danger-soft);
      color: var(--color-danger);
    }

    h2 {
      margin-bottom: var(--space-3);
      font-size: 1.25rem;
    }

    p {
      margin-bottom: var(--space-4);
    }
  `,
})
export class ErrorState {
  readonly title = input.required<string>();
  readonly message = input.required<string>();
  readonly retryLabel = input<string | null>(null);
  readonly retry = output<void>();
}
