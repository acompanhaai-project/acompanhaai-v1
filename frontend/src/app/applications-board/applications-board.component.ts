import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  Injector,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { toApiError } from '../core/http/api-error';
import {
  Application,
  ApplicationPage,
  ApplicationStatus,
  STATUS_LABELS,
} from './application.models';
import { ApplicationsApi } from './applications-api';

interface Lane {
  readonly id: string;
  readonly label: string;
  readonly statuses: readonly ApplicationStatus[];
}

const ACTIVE: readonly Lane[] = [
  { id: 'applied', label: 'Candidatura realizada', statuses: ['APPLIED'] },
  { id: 'screening', label: 'Triagem', statuses: ['SCREENING'] },
  { id: 'interview', label: 'Entrevista', statuses: ['INTERVIEW'] },
  { id: 'offer', label: 'Proposta', statuses: ['OFFER'] },
];

const OTHER: readonly Lane[] = [
  { id: 'saved', label: 'Salvas', statuses: ['SAVED'] },
  { id: 'finished', label: 'Finalizadas', statuses: ['HIRED', 'REJECTED', 'WITHDRAWN'] },
];

interface CardState {
  readonly destination?: ApplicationStatus;
  readonly busy?: 'move' | 'refresh';
  readonly error?: string;
  readonly needsRefresh?: boolean;
}

@Component({
  selector: 'app-applications-board',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './applications-board.component.html',
  styleUrl: './applications-board.component.scss',
})
export class ApplicationsBoardComponent {
  private readonly api = inject(ApplicationsApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);
  private readonly applications = signal<readonly Application[]>([]);
  private readonly page = signal<ApplicationPage | null>(null);

  readonly labels = STATUS_LABELS;
  readonly destinations = Object.keys(STATUS_LABELS) as ApplicationStatus[];
  readonly cards = signal<Partial<Record<number, CardState>>>({});
  readonly loading = signal(false);
  readonly listError = signal('');
  readonly announcement = signal('');
  readonly loaded = computed(() => this.applications().length);
  readonly total = computed(() => this.page()?.totalElements ?? 0);
  readonly hasPage = computed(() => this.page() !== null);
  readonly hasMore = computed(() => {
    const page = this.page();
    return page !== null && page.page + 1 < page.totalPages;
  });
  readonly groups = computed(() => {
    const applications = this.applications();
    const lanes = (definitions: readonly Lane[]) =>
      definitions.map((lane) => ({
        ...lane,
        applications: applications.filter((application) =>
          lane.statuses.includes(application.status),
        ),
      }));
    return [
      { id: 'active', title: 'Em andamento', lanes: lanes(ACTIVE) },
      { id: 'other', title: 'Salvas e finalizadas', lanes: lanes(OTHER) },
    ];
  });

  constructor() {
    this.loadNextPage();
  }

  loadNextPage(): void {
    const previous = this.page();
    if (this.loading() || (previous && !this.hasMore())) {
      return;
    }
    this.listError.set('');
    this.loading.set(true);
    this.api
      .list(previous ? previous.page + 1 : 0)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.applications.update((current) => {
            const loaded = new Set(current.map((application) => application.id));
            return [
              ...current,
              ...page.content.filter((application) => !loaded.has(application.id)),
            ];
          });
          this.page.set(page);
        },
        error: (error: unknown) => this.listError.set(toApiError(error).message),
      });
  }

  choose(id: number, value: string): void {
    const destination = Object.hasOwn(this.labels, value)
      ? (value as ApplicationStatus)
      : undefined;
    this.updateCard(id, { destination });
  }

  move(application: Application): void {
    const card = this.cards()[application.id];
    const destination = card?.destination;
    if (!destination || destination === application.status || card?.busy || card?.needsRefresh) {
      return;
    }

    this.announcement.set('');
    this.updateCard(application.id, { busy: 'move', error: undefined });
    this.api
      .move(application, destination)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.updateCard(application.id, { busy: undefined })),
      )
      .subscribe({
        next: (updated) => {
          this.replaceApplication(updated);
          this.updateCard(application.id, { destination: undefined, needsRefresh: false });
          this.announcement.set(`Candidatura movida para ${this.labels[updated.status]}.`);
          this.focusAfterRender(`application-${updated.id}`);
        },
        error: (cause: unknown) => {
          const error = toApiError(cause);
          this.updateCard(application.id, {
            error: error.message,
            needsRefresh: error.status === 409 || error.status === 404 || error.status === 0,
          });
        },
      });
  }

  refresh(application: Application): void {
    const card = this.cards()[application.id];
    if (!card?.needsRefresh || card.busy) {
      return;
    }
    this.announcement.set('');
    this.updateCard(application.id, { busy: 'refresh' });
    this.api
      .get(application.id)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.updateCard(application.id, { busy: undefined })),
      )
      .subscribe({
        next: (updated) => {
          this.replaceApplication(updated);
          this.updateCard(application.id, {
            destination: card.destination === updated.status ? undefined : card.destination,
            error: undefined,
            needsRefresh: false,
          });
          this.announcement.set('Candidatura atualizada. Confira os dados antes de mover.');
          this.focusAfterRender(`application-${updated.id}`);
        },
        error: (cause: unknown) => {
          const error = toApiError(cause);
          if (error.status === 404) {
            this.applications.update((items) => items.filter((item) => item.id !== application.id));
            this.page.update(
              (page) => page && { ...page, totalElements: Math.max(0, page.totalElements - 1) },
            );
            this.announcement.set('Essa candidatura não está mais disponível.');
            this.focusAfterRender('board-title');
          } else {
            this.updateCard(application.id, { error: error.message });
          }
        },
      });
  }

  private replaceApplication(application: Application): void {
    this.applications.update((items) => [
      application,
      ...items.filter((item) => item.id !== application.id),
    ]);
  }

  private updateCard(id: number, patch: CardState): void {
    this.cards.update((cards) => ({ ...cards, [id]: { ...cards[id], ...patch } }));
  }

  private focusAfterRender(id: string): void {
    afterNextRender(() => this.document.getElementById(id)?.focus(), { injector: this.injector });
  }
}
