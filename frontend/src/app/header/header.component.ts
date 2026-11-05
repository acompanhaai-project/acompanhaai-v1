import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';

@Component({
  selector: 'app-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent {
  private readonly doc = inject(DOCUMENT);

  alternarTema(): void {
    const root = this.doc.documentElement;
    const escuro = root.dataset['theme']
      ? root.dataset['theme'] === 'dark'
      : this.doc.defaultView!.matchMedia('(prefers-color-scheme: dark)').matches;
    root.dataset['theme'] = escuro ? 'light' : 'dark';
  }
}
