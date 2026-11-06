import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './header/header.component';
import { NeonCursorComponent } from './neon-cursor/neon-cursor.component';
import { SessionAccess } from './core/auth/session-access';
import { SessionNavigation } from './core/auth/session-navigation';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent, NeonCursorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-header />
    @if (showSessionProgress()) {
      <main class="session-progress" role="status">Verificando sua sessão…</main>
    }
    <router-outlet />
    <app-neon-cursor />
  `,
  styles: `
    .session-progress {
      padding: 48px 20px;
      text-align: center;
      color: var(--muted);
    }
  `,
})
export class AppComponent {
  private readonly access = inject(SessionAccess);
  private readonly outlet = viewChild(RouterOutlet);
  protected readonly showSessionProgress = computed(
    () => this.access.checking() && !this.outlet()?.isActivated,
  );

  constructor() {
    inject(SessionNavigation);
  }
}
