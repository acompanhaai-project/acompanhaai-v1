import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { SessionState } from '../core/auth/session-state';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent {
  private readonly doc = inject(DOCUMENT);
  protected readonly authenticated = inject(SessionState).authenticated;

  alternarTema(): void {
    const root = this.doc.documentElement;
    const escuro = root.dataset['theme']
      ? root.dataset['theme'] === 'dark'
      : this.doc.defaultView!.matchMedia('(prefers-color-scheme: dark)').matches;
    root.dataset['theme'] = escuro ? 'light' : 'dark';
  }
}
