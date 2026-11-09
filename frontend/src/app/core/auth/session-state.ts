import { computed, Injectable, signal } from '@angular/core';
import { SessionStatus, User } from './session.models';

@Injectable({ providedIn: 'root' })
export class SessionState {
  private readonly currentUser = signal<User | null>(null);
  private readonly currentStatus = signal<SessionStatus>('unknown');
  private readonly currentRevision = signal(0);
  private readonly currentExpiry = signal<number | null>(null);

  readonly user = this.currentUser.asReadonly();
  readonly status = this.currentStatus.asReadonly();
  readonly revision = this.currentRevision.asReadonly();
  readonly expiredRevision = this.currentExpiry.asReadonly();
  readonly authenticated = computed(() => this.status() === 'authenticated');

  authenticate(user: User): void {
    this.currentExpiry.set(null);
    this.currentUser.set(user);
    this.currentStatus.set('authenticated');
    this.currentRevision.update((revision) => revision + 1);
  }

  updateUser(user: User, revision: number): boolean {
    if (this.revision() !== revision || this.user()?.id !== user.id) {
      return false;
    }

    this.currentUser.set(user);
    return true;
  }

  markUnknown(): void {
    this.currentExpiry.set(null);
    this.currentUser.set(null);
    this.currentStatus.set('unknown');
    this.currentRevision.update((revision) => revision + 1);
  }

  markAnonymous(): void {
    this.currentExpiry.set(null);
    this.currentUser.set(null);
    this.currentStatus.set('anonymous');
    this.currentRevision.update((revision) => revision + 1);
  }

  markExpired(): void {
    this.markAnonymous();
    this.currentExpiry.set(this.currentRevision());
  }
}
