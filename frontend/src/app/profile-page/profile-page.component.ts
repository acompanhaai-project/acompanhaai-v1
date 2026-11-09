import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize, Observable, Subscription } from 'rxjs';
import { MAX_PHOTO_BYTES, ProfilePhotoApi } from '../core/auth/profile-photo-api';
import { SessionApi } from '../core/auth/session-api';
import { SessionState } from '../core/auth/session-state';
import { toApiError } from '../core/http/api-error';
import { NeonCardDirective } from '../shared/neon-card.directive';
import { senhaForte, senhasIguais } from '../shared/senha';

type Area = 'details' | 'password' | 'photo' | 'delete';

@Component({
  selector: 'app-profile-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, NeonCardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './profile-page.component.html',
  styleUrl: './profile-page.component.scss',
})
export class ProfilePageComponent {
  private readonly api = inject(SessionApi);
  private readonly photos = inject(ProfilePhotoApi);
  private readonly state = inject(SessionState);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private photoRequest?: Subscription;

  readonly user = this.state.user;
  readonly initials = computed(() => {
    const names = this.user()?.name.trim().split(/\s+/) ?? [];
    const first = names[0]?.[0] ?? '';
    const last = names.length > 1 ? names.at(-1)?.[0] : '';
    return `${first}${last ?? ''}`.toLocaleUpperCase('pt-BR');
  });
  readonly busy = signal<Area | null>(null);
  readonly feedback = signal<{ area: Area; message: string; error: boolean } | null>(null);
  readonly photoLoading = signal(false);
  readonly photoLoadError = signal('');
  readonly savedPhoto = signal<string | null>(null);
  readonly preview = signal<string | null>(null);
  readonly selected = signal<File | null>(null);

  readonly profile = new FormGroup({
    name: new FormControl(this.user()?.name ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(2), Validators.maxLength(100)],
    }),
    email: new FormControl(this.user()?.email ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(254)],
    }),
  });
  readonly password = new FormGroup(
    {
      currentPassword: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      senha: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, senhaForte],
      }),
      confirma: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    },
    { validators: senhasIguais },
  );
  readonly confirmation = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });

  constructor() {
    if (this.user()?.photoUrl) {
      this.loadPhoto();
    }
    this.destroyRef.onDestroy(() => {
      this.photoRequest?.unsubscribe();
      this.replaceObjectUrl(this.savedPhoto, null);
      this.replaceObjectUrl(this.preview, null);
    });
  }

  saveProfile(): void {
    if (this.busy()) {
      return;
    }
    this.profile.controls.name.setValue(this.profile.controls.name.value.trim());
    this.profile.controls.email.setValue(this.profile.controls.email.value.trim());
    this.profile.markAllAsTouched();
    if (this.profile.invalid) {
      this.feedback.set({ area: 'details', error: true, message: 'Confira o nome e o e-mail.' });
      return;
    }

    this.run('details', this.api.updateProfile(this.profile.getRawValue()), (user) => {
      this.profile.reset({ name: user.name, email: user.email });
      this.feedback.set({ area: 'details', error: false, message: 'Dados atualizados.' });
    });
  }

  changePassword(): void {
    this.password.markAllAsTouched();
    if (this.password.invalid) {
      this.feedback.set({ area: 'password', error: true, message: 'Confira os campos de senha.' });
      return;
    }

    const { currentPassword, senha } = this.password.getRawValue();
    this.run('password', this.api.changePassword({ currentPassword, newPassword: senha }), () => {
      this.password.reset();
      void this.router.navigateByUrl('/entrar?notice=password-changed', { replaceUrl: true });
    });
  }

  selectPhoto(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.busy()) {
      return;
    }
    this.cancelPreview();
    if (
      !['image/jpeg', 'image/png'].includes(file.type) ||
      file.size === 0 ||
      file.size > MAX_PHOTO_BYTES
    ) {
      this.feedback.set({
        area: 'photo',
        error: true,
        message: 'Selecione uma imagem JPEG ou PNG de até 2 MiB.',
      });
      return;
    }
    this.feedback.set(null);
    this.selected.set(file);
    this.replaceObjectUrl(this.preview, URL.createObjectURL(file));
  }

  cancelPreview(): void {
    this.selected.set(null);
    this.replaceObjectUrl(this.preview, null);
  }

  savePhoto(): void {
    const file = this.selected();
    if (file) {
      this.run('photo', this.photos.replace(file), () => {
        this.cancelPreview();
        this.loadPhoto();
        this.feedback.set({ area: 'photo', error: false, message: 'Foto atualizada.' });
      });
    }
  }

  removePhoto(): void {
    this.run('photo', this.photos.remove(), () => {
      this.photoRequest?.unsubscribe();
      this.photoLoadError.set('');
      this.cancelPreview();
      this.replaceObjectUrl(this.savedPhoto, null);
      this.feedback.set({ area: 'photo', error: false, message: 'Foto removida.' });
    });
  }

  loadPhoto(): void {
    this.photoRequest?.unsubscribe();
    this.photoLoadError.set('');
    this.photoLoading.set(true);
    this.photoRequest = this.photos
      .load()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.photoLoading.set(false)),
      )
      .subscribe({
        next: (photo) =>
          this.replaceObjectUrl(this.savedPhoto, photo ? URL.createObjectURL(photo) : null),
        error: () => this.photoLoadError.set('Não foi possível carregar a foto.'),
      });
  }

  openDeletion(dialog: HTMLDialogElement): void {
    this.confirmation.reset();
    this.feedback.set(null);
    dialog.showModal();
  }

  confirmDeletion(event: SubmitEvent, dialog: HTMLDialogElement): void {
    event.preventDefault();
    this.run('delete', this.api.deleteAccount(this.confirmation.value), () => {
      dialog.close();
      void this.router.navigateByUrl('/entrar?notice=account-deleted', { replaceUrl: true });
    });
  }

  preventBusyClose(event: Event): void {
    if (this.busy() === 'delete') {
      event.preventDefault();
    }
  }

  private run<T>(area: Area, request: Observable<T>, success: (value: T) => void): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(area);
    this.feedback.set(null);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(null)),
      )
      .subscribe({
        next: success,
        error: (cause: unknown) => {
          const error = toApiError(cause);
          const fields = error.fieldErrors.map((field) => field.message).join(' ');
          this.feedback.set({ area, error: true, message: fields || error.message });
        },
      });
  }

  private replaceObjectUrl(target: typeof this.savedPhoto, value: string | null): void {
    const previous = target();
    target.set(value);
    if (previous) {
      URL.revokeObjectURL(previous);
    }
  }
}
