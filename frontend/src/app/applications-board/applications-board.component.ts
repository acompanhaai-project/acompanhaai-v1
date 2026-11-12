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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, Subscription } from 'rxjs';
import { toApiError } from '../core/http/api-error';
import {
  Application,
  ApplicationPage,
  ApplicationStatus,
  STATUS_LABELS,
} from './application.models';
import {
  ApplicationFilters,
  ApplicationListQuery,
  filtersFrom,
  MAX_QUERY_LENGTH,
  NO_FILTERS,
  queryParamsOf,
} from './application-list-query';
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
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly listQuery = inject(ApplicationListQuery);
  private request?: Subscription;
  private announceResults = false;
  private readonly applications = signal<readonly Application[]>([]);
  private readonly page = signal<ApplicationPage | null>(null);

  readonly labels = STATUS_LABELS;
  readonly maxQueryLength = MAX_QUERY_LENGTH;
  readonly filters = signal<ApplicationFilters>(filtersFrom(this.route.snapshot.queryParamMap));
  readonly filtered = computed(() => this.filters().status !== null || this.filters().q !== '');
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
    const status = this.filters().status;
    const lanes = (definitions: readonly Lane[]) =>
      definitions
        .filter((lane) => status === null || lane.statuses.includes(status))
        .map((lane) => ({
          ...lane,
          applications: applications.filter((application) =>
            lane.statuses.includes(application.status),
          ),
        }));
    return [
      { id: 'active', title: 'Em andamento', lanes: lanes(ACTIVE) },
      { id: 'other', title: 'Salvas e finalizadas', lanes: lanes(OTHER) },
    ].filter((group) => group.lanes.length > 0);
  });

  constructor() {
    this.listQuery.remember(this.filters());
    this.loadNextPage();
  }

  search(value: string): void {
    this.applyFilters({ ...this.filters(), q: value.trim().slice(0, MAX_QUERY_LENGTH) });
  }

  filterByStatus(value: string): void {
    const status = Object.hasOwn(this.labels, value) ? (value as ApplicationStatus) : null;
    this.applyFilters({ ...this.filters(), status });
  }

  clearFilters(): void {
    this.applyFilters(NO_FILTERS);
    this.focusAfterRender('filter-q');
  }

  private applyFilters(next: ApplicationFilters): void {
    const current = this.filters();
    if (next.status === current.status && next.q === current.q) {
      return;
    }
    this.filters.set(next);
    this.listQuery.remember(next);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: queryParamsOf(next),
      replaceUrl: true,
    });
    this.request?.unsubscribe();
    this.applications.set([]);
    this.page.set(null);
    this.cards.set({});
    this.announcement.set('');
    this.announceResults = true;
    this.loadNextPage();
  }

  loadNextPage(): void {
    const previous = this.page();
    if (this.loading() || (previous && !this.hasMore())) {
      return;
    }
    this.listError.set('');
    this.loading.set(true);
    this.request = this.api
      .list(previous ? previous.page + 1 : 0, this.filters())
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
          if (this.announceResults && page.page === 0) {
            this.announceResults = false;
            this.announcement.set(
              page.totalElements === 1
                ? '1 candidatura encontrada.'
                : `${page.totalElements} candidaturas encontradas.`,
            );
          }
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
          this.updateCard(application.id, { destination: undefined, needsRefresh: false });
          const status = this.filters().status;
          if (status !== null && updated.status !== status) {
            this.removeApplication(updated.id);
            this.announcement.set(
              `Candidatura movida para ${this.labels[updated.status]}. ` +
                'Ela não aparece com o filtro de etapa atual.',
            );
            this.focusAfterRender('board-title');
            return;
          }
          this.replaceApplication(updated);
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
          const status = this.filters().status;
          if (status !== null && updated.status !== status) {
            this.removeApplication(updated.id);
            this.announcement.set('A candidatura mudou de etapa e não aparece com o filtro atual.');
            this.focusAfterRender('board-title');
            return;
          }
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
            this.removeApplication(application.id);
            this.announcement.set('Essa candidatura não está mais disponível.');
            this.focusAfterRender('board-title');
          } else {
            this.updateCard(application.id, { error: error.message });
          }
        },
      });
  }

  private removeApplication(id: number): void {
    this.applications.update((items) => items.filter((item) => item.id !== id));
    this.page.update(
      (page) => page && { ...page, totalElements: Math.max(0, page.totalElements - 1) },
    );
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
