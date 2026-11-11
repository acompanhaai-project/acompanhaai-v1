import { DecimalPipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  catchError,
  defer,
  EMPTY,
  expand,
  map,
  Observable,
  startWith,
  Subject,
  switchMap,
  timer,
} from 'rxjs';
import { ApiError, ApiFieldError } from '../core/http/api-error';
import { ACTIVE_STATUSES, ImportInput, ImportInputType, ImportTask } from './import.models';
import { ImportsApi } from './imports-api';

const WORK_MODES = { REMOTE: 'Remoto', HYBRID: 'Híbrido', ONSITE: 'Presencial' } as const;
const SALARY_PERIODS = {
  HOUR: 'hora',
  DAY: 'dia',
  WEEK: 'semana',
  MONTH: 'mês',
  YEAR: 'ano',
} as const;

/** Intervalo crescente entre consultas, limitado a 10 segundos. */
export const POLL_DELAYS_MS = [1000, 2000, 4000, 8000, 10000] as const;
/** Após esse tempo, o acompanhamento pausa até uma nova ação explícita. */
export const MAX_WATCH_MS = 3 * 60 * 1000;

registerLocaleData(localePt);

const BLOCKED = new Set(['SOURCE_REQUIRES_LOGIN', 'SOURCE_BLOCKED', 'SOURCE_REQUIRES_JAVASCRIPT']);

type Phase = 'form' | 'loading' | 'active' | 'ready' | 'attention' | 'completed' | 'missing';

function delayFor(attempt: number): number {
  return POLL_DELAYS_MS[Math.min(attempt, POLL_DELAYS_MS.length - 1)];
}

function positiveId(value: string | null): number | null {
  const id = Number(value);
  return value !== null && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(id) ? id : null;
}

@Component({
  selector: 'app-import-page',
  imports: [RouterLink, ReactiveFormsModule, DecimalPipe],
  templateUrl: './import-page.component.html',
  styleUrl: './import-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ImportsApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly resume = new Subject<void>();

  protected readonly workModes = WORK_MODES;
  protected readonly periods = SALARY_PERIODS;
  protected readonly task = signal<ImportTask | null>(null);
  protected readonly taskId = signal<number | null>(null);
  protected readonly loading = signal(false);
  protected readonly missing = signal(false);
  protected readonly paused = signal(false);
  protected readonly expired = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly watchFailed = signal(false);
  protected readonly overloaded = signal(false);
  protected readonly notice = signal('');
  protected readonly fieldErrors = signal<readonly ApiFieldError[]>([]);

  protected readonly form = new FormGroup({
    inputType: new FormControl<ImportInputType>('URL', { nonNullable: true }),
    url: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(2048)] }),
    text: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(30000)] }),
  });

  protected readonly fallback = new FormGroup({
    text: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(30000)],
    }),
  });

  protected readonly phase = computed<Phase>(() => {
    if (this.taskId() === null) {
      return 'form';
    }
    if (this.missing() || this.expired()) {
      return 'missing';
    }
    const task = this.task();
    if (!task) {
      return 'loading';
    }
    if (ACTIVE_STATUSES.has(task.status)) {
      return 'active';
    }
    if (task.status === 'READY_FOR_REVIEW') {
      return 'ready';
    }
    return task.status === 'COMPLETED' ? 'completed' : 'attention';
  });

  protected readonly failureTitle = computed(() => {
    const code = this.task()?.failure?.code ?? '';
    if (BLOCKED.has(code)) {
      return 'O site não permitiu a leitura da vaga';
    }
    if (code === 'SOURCE_NO_USABLE_CONTENT') {
      return 'Não encontramos a descrição na página';
    }
    if (code === 'SOURCE_NOT_ALLOWED') {
      return 'Este link não pode ser importado';
    }
    return 'Não foi possível processar a importação';
  });

  constructor() {
    this.route.paramMap
      .pipe(
        map((params) => params.get('id')),
        switchMap((value) => {
          this.reset();
          if (value === null) {
            this.taskId.set(null);
            return EMPTY;
          }
          const id = positiveId(value);
          this.taskId.set(id ?? -1);
          if (id === null) {
            this.missing.set(true);
            return EMPTY;
          }
          return this.resume.pipe(
            startWith(undefined),
            switchMap(() => this.watch(id)),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((task) => this.task.set(task));
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    const { inputType, url, text } = this.form.getRawValue();
    const value = inputType === 'URL' ? url.trim() : text;
    if (!value.trim()) {
      this.error.set(inputType === 'URL' ? 'Informe o link da vaga.' : 'Cole a descrição da vaga.');
      return;
    }
    this.start(inputType === 'URL' ? { inputType, url: value } : { inputType, text: value });
  }

  protected submitFallback(): void {
    if (this.busy()) {
      return;
    }
    if (this.fallback.invalid) {
      this.error.set('Cole a descrição da vaga.');
      return;
    }
    this.start({ inputType: 'TEXT', text: this.fallback.getRawValue().text });
  }

  protected keepWatching(): void {
    this.paused.set(false);
    this.watchFailed.set(false);
    this.error.set('');
    this.resume.next();
  }

  protected cancel(): void {
    const id = this.taskId();
    if (id === null || id < 1 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api
      .cancel(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          void this.router.navigate(['/candidaturas/importar'], {
            replaceUrl: true,
            state: { notice: 'Importação cancelada.' },
          });
        },
        error: (error: unknown) => {
          this.busy.set(false);
          if (error instanceof ApiError && error.status === 404) {
            this.missing.set(true);
            return;
          }
          this.showError(error);
          this.resume.next();
        },
      });
  }

  protected excerpt(text: string | undefined): string {
    if (!text) {
      return '';
    }
    return text.length > 600 ? `${text.slice(0, 600).trimEnd()}…` : text;
  }

  private start(input: ImportInput): void {
    this.busy.set(true);
    this.clearErrors();
    this.api
      .create(input)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (task) => {
          this.busy.set(false);
          this.fallback.reset();
          void this.router.navigate(['/candidaturas/importar', task.id], {
            replaceUrl: this.taskId() !== null,
          });
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.showCreateError(error);
        },
      });
  }

  private watch(id: number): Observable<ImportTask> {
    const started = Date.now();
    let attempt = 0;
    const fetch = defer(() => this.api.get(id));
    return fetch.pipe(
      expand((task) => {
        if (!ACTIVE_STATUSES.has(task.status)) {
          return EMPTY;
        }
        if (Date.parse(task.expiresAt) <= Date.now()) {
          this.expired.set(true);
          return EMPTY;
        }
        if (Date.now() - started >= MAX_WATCH_MS) {
          this.paused.set(true);
          return EMPTY;
        }
        return timer(delayFor(attempt++)).pipe(switchMap(() => fetch));
      }),
      catchError((error: unknown) => {
        if (error instanceof ApiError && error.status === 404) {
          this.missing.set(true);
        } else {
          this.watchFailed.set(true);
          this.showError(error);
        }
        return EMPTY;
      }),
    );
  }

  private reset(): void {
    this.task.set(null);
    this.missing.set(false);
    this.expired.set(false);
    this.paused.set(false);
    this.busy.set(false);
    this.watchFailed.set(false);
    this.clearErrors();
    const state = this.router.currentNavigation()?.extras.state;
    this.notice.set(typeof state?.['notice'] === 'string' ? state['notice'] : '');
  }

  private clearErrors(): void {
    this.error.set('');
    this.overloaded.set(false);
    this.fieldErrors.set([]);
  }

  private showCreateError(error: unknown): void {
    if (!(error instanceof ApiError)) {
      this.showError(error);
      return;
    }
    if (error.code === 'IMPORT_OVERLOADED') {
      this.overloaded.set(true);
      this.error.set(
        'A importação está sobrecarregada no momento. Cadastre a vaga manualmente ou tente mais tarde.',
      );
      return;
    }
    if (error.code === 'IMPORT_QUOTA_EXCEEDED' && error.retryAfterSeconds !== null) {
      const minutes = Math.max(1, Math.ceil(error.retryAfterSeconds / 60));
      this.error.set(
        `Você atingiu o limite de importações por hora. Tente novamente em ${minutes} ${
          minutes === 1 ? 'minuto' : 'minutos'
        } ou cadastre a vaga manualmente.`,
      );
      return;
    }
    this.fieldErrors.set(error.fieldErrors);
    this.error.set(error.message);
  }

  private showError(error: unknown): void {
    this.error.set(
      error instanceof ApiError ? error.message : 'Não foi possível concluir a operação.',
    );
  }
}
