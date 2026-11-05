import { Component, input } from '@angular/core';

@Component({
  selector: 'app-form-field',
  template: `
    <div class="form-field">
      <label [for]="controlId()">{{ label() }}</label>
      @if (hint()) {
        <p class="hint" [id]="controlId() + '-hint'">{{ hint() }}</p>
      }
      <ng-content />
      @if (error()) {
        <p class="error" [id]="controlId() + '-error'" role="alert">{{ error() }}</p>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    label {
      display: block;
      margin-bottom: var(--space-2);
      font-weight: 700;
    }

    .hint,
    .error {
      margin-bottom: var(--space-2);
      font-size: 0.875rem;
    }

    .hint {
      color: var(--color-muted);
    }

    .error {
      margin-top: var(--space-2);
      color: var(--color-danger);
    }
  `,
})
export class FormField {
  readonly controlId = input.required<string>();
  readonly label = input.required<string>();
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
}
