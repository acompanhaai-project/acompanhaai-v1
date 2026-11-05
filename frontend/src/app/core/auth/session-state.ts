import { computed, Injectable, signal } from '@angular/core';
import { SessionStatus, User } from './session.models';

@Injectable({ providedIn: 'root' })
export class SessionState {
  private readonly currentUser = signal<User | null>(null);
  private readonly currentStatus = signal<SessionStatus>('unknown');
  private readonly currentRevision = signal(0);

  readonly user = this.currentUser.asReadonly();
  readonly status = this.currentStatus.asReadonly();
  readonly revision = this.currentRevision.asReadonly();
  readonly authenticated = computed(() => this.status() === 'authenticated');

  authenticate(user: User): void {
    this.currentUser.set(user);
    this.currentStatus.set('authenticated');
    this.currentRevision.update((revision) => revision + 1);
  }

  markUnknown(): void {
    this.currentUser.set(null);
    this.currentStatus.set('unknown');
    this.currentRevision.update((revision) => revision + 1);
  }

  markAnonymous(): void {
    this.currentUser.set(null);
    this.currentStatus.set('anonymous');
    this.currentRevision.update((revision) => revision + 1);
  }
}
