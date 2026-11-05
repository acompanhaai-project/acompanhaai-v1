import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { REQUISITOS } from './senha';

/** Medidor de força + checklist de requisitos. Uso: <app-requisitos-senha [senha]="valor" /> */
@Component({
  selector: 'app-requisitos-senha',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './requisitos-senha.component.scss',
  template: `
    @if (senha()) {
      <div class="forca" [attr.data-nivel]="forca().nivel">
        <div class="forca__barras" aria-hidden="true">
          @for (i of [0, 1, 2, 3]; track i) {
            <span [class.on]="i < forca().nivel"></span>
          }
        </div>
        <span class="forca__rotulo"
          >Força da senha: <strong>{{ forca().rotulo }}</strong></span
        >
      </div>
    }

    <ul id="requisitos" class="reqs" aria-label="Requisitos da senha">
      @for (r of requisitos(); track r.id) {
        <li [class.ok]="r.ok">
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="9" />
            <path d="M7.5 12.5l3 3 6-6.5" />
          </svg>
          {{ r.texto }}<span class="sr-only">{{ r.ok ? ' (atendido)' : ' (pendente)' }}</span>
        </li>
      }
    </ul>
  `,
})
export class RequisitosSenhaComponent {
  readonly senha = input<string>('');

  readonly requisitos = computed(() => {
    const s = this.senha();
    return REQUISITOS.map((r) => ({ id: r.id, texto: r.texto, ok: r.ok(s) }));
  });

  readonly forca = computed(() => {
    const n = this.requisitos().filter((r) => r.ok).length;
    const nivel = n === 0 ? 0 : n <= 2 ? 1 : n === 3 ? 2 : n === 4 ? 3 : 4;
    return { nivel, rotulo: ['', 'Fraca', 'Média', 'Boa', 'Forte'][nivel] };
  });
}
