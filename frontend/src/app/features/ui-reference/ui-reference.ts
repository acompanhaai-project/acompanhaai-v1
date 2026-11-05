import { Component, computed, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { ErrorState } from '../../shared/ui/error-state';
import { FormField } from '../../shared/ui/form-field';
import { LoadingState } from '../../shared/ui/loading-state';

@Component({
  selector: 'app-ui-reference',
  imports: [ReactiveFormsModule, EmptyState, ErrorState, FormField, LoadingState],
  templateUrl: './ui-reference.html',
  styles: `
    .reference-section + .reference-section {
      margin-top: var(--space-7);
    }

    .controls {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-3);
      margin-bottom: var(--space-5);
    }

    form {
      display: grid;
      gap: var(--space-5);
      max-width: 40rem;
    }

    .confirmation {
      color: var(--color-success);
    }
  `,
})
export class UiReference {
  protected readonly state = signal<'empty' | 'loading' | 'error'>('empty');
  protected readonly confirmed = signal(false);
  protected readonly titleControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)],
  });
  protected readonly previewForm = new FormGroup({ title: this.titleControl });

  private readonly titleEvents = toSignal(this.titleControl.events);

  protected readonly titleError = computed(() => {
    this.titleEvents();
    if (!this.titleControl.touched || !this.titleControl.invalid) {
      return null;
    }
    return 'Informe um título com até 255 caracteres.';
  });

  protected validatePreview(): void {
    this.titleControl.markAsTouched();
    this.confirmed.set(this.titleControl.valid);
  }
}
