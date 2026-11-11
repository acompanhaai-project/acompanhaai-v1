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
import { ApplicationInput, Job } from '../applications-board/application.models';
import { ApiError, ApiFieldError } from '../core/http/api-error';
import {
  ACTIVE_STATUSES,
  ImportInput,
  ImportInputType,
  ImportProposal,
  ImportTask,
} from './import.models';
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

const BLOCKED = new Set(['SOURCE_REQUIRES_LOGIN', 'SOURCE_BLOCKED', 'SOURCE_REQUIRES_JAVASCRIPT']);

type Phase = 'form' | 'loading' | 'active' | 'ready' | 'attention' | 'completed' | 'missing';

type Decision = 'SAVED' | 'APPLIED';

function optional(value: string): string | null {
  return value.trim() || null;
}

function skills(value: string): string[] {
  return value
    .split('\n')
    .map((skill) => skill.trim())
    .filter(Boolean);
}

function today(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function delayFor(attempt: number): number {
  return POLL_DELAYS_MS[Math.min(attempt, POLL_DELAYS_MS.length - 1)];
}

function positiveId(value: string | null): number | null {
  const id = Number(value);
  return value !== null && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(id) ? id : null;
}

@Component({
  selector: 'app-import-page',
  imports: [RouterLink, ReactiveFormsModule],
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
  protected readonly workModeOptions = Object.keys(WORK_MODES) as NonNullable<Job['workMode']>[];
  protected readonly periods = SALARY_PERIODS;
  protected readonly periodOptions = Object.keys(SALARY_PERIODS) as NonNullable<
    Job['salaryPeriod']
  >[];
  protected readonly missingFields = signal<ReadonlySet<string>>(new Set());
  private readonly evidence = signal<NonNullable<ImportProposal['evidence']>>({});
  private reviewedTask: number | null = null;
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

  protected readonly review = new FormGroup({
    title: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)],
    }),
    companyName: new FormControl('', { nonNullable: true }),
    sourceUrl: new FormControl('', { nonNullable: true }),
    description: new FormControl('', { nonNullable: true }),
    location: new FormControl('', { nonNullable: true }),
    workMode: new FormControl<Job['workMode']>(null),
    salary: new FormControl<number | null>(null),
    salaryPeriod: new FormControl<Job['salaryPeriod']>(null),
    hardSkills: new FormControl('', { nonNullable: true }),
    softSkills: new FormControl('', { nonNullable: true }),
    decision: new FormControl<Decision>('SAVED', { nonNullable: true }),
    appliedOn: new FormControl(today(), { nonNullable: true }),
    notes: new FormControl('', { nonNullable: true }),
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
      .subscribe((task) => {
        this.task.set(task);
        if (task.status === 'READY_FOR_REVIEW' && task.result && this.reviewedTask !== task.id) {
          this.fillReview(task.id, task.result);
        }
      });
  }

  protected save(): void {
    const id = this.taskId();
    if (id === null || id < 1 || this.busy()) {
      return;
    }
    this.clearErrors();
    const value = this.review.getRawValue();
    if (this.review.invalid) {
      this.review.markAllAsTouched();
      this.error.set(
        this.review.controls.title.invalid
          ? 'Preencha o título da vaga com até 255 caracteres.'
          : 'Confira os campos do formulário e tente novamente.',
      );
      return;
    }
    if (value.salary !== null && !value.salaryPeriod) {
      this.error.set('Selecione o período do salário.');
      return;
    }

    const input: ApplicationInput = {
      job: {
        title: value.title.trim(),
        companyName: optional(value.companyName),
        sourceUrl: optional(value.sourceUrl),
        description: optional(value.description),
        location: optional(value.location),
        workMode: value.workMode,
        salary: value.salary,
        salaryCurrency: value.salary === null ? null : 'BRL',
        salaryPeriod: value.salary === null ? null : value.salaryPeriod,
        hardSkills: skills(value.hardSkills),
        softSkills: skills(value.softSkills),
      },
      status: value.decision,
      appliedOn: value.decision === 'SAVED' ? null : optional(value.appliedOn),
      notes: optional(value.notes),
    };

    this.busy.set(true);
    this.api
      .confirm(id, input)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (application) => {
          this.busy.set(false);
          void this.router.navigate(['/candidaturas', application.id], { replaceUrl: true });
        },
        error: (error: unknown) => {
          this.busy.set(false);
          if (error instanceof ApiError && error.status === 404) {
            this.missing.set(true);
            return;
          }
          if (error instanceof ApiError && error.code === 'JOB_URL_ALREADY_EXISTS') {
            this.error.set(
              'Você já cadastrou uma vaga com esse link. Altere o link ou abra a candidatura existente nas suas candidaturas.',
            );
            return;
          }
          this.showCreateError(error);
          if (error instanceof ApiError && error.code === 'IMPORT_NOT_READY') {
            this.resume.next();
          }
        },
      });
  }

  protected isMissing(field: string): boolean {
    return this.missingFields().has(field);
  }

  protected evidenceFor(field: string): string | null {
    const evidence = this.evidence()[field];
    if (!evidence) {
      return null;
    }
    if (evidence.source === 'STRUCTURED_DATA') {
      return 'Origem: dados estruturados da página.';
    }
    return evidence.excerpt ? `Trecho da vaga: “${evidence.excerpt}”` : null;
  }

  private fillReview(id: number, result: ImportProposal): void {
    this.reviewedTask = id;
    this.evidence.set(result.evidence ?? {});
    this.review.reset({
      title: result.title ?? '',
      companyName: result.companyName ?? '',
      sourceUrl: result.sourceUrl ?? '',
      description: result.description ?? '',
      location: result.location ?? '',
      workMode: result.workMode ?? null,
      salary: result.salary ?? null,
      salaryPeriod: result.salaryPeriod ?? null,
      hardSkills: (result.hardSkills ?? []).join('\n'),
      softSkills: (result.softSkills ?? []).join('\n'),
      decision: 'SAVED',
      appliedOn: today(),
      notes: '',
    });
    const checked = [
      'title',
      'companyName',
      'location',
      'workMode',
      'salary',
      'hardSkills',
      'softSkills',
    ] as const;
    this.missingFields.set(
      new Set(
        checked.filter((field) => {
          const value = result[field];
          return value === undefined || value === null || (Array.isArray(value) && !value.length);
        }),
      ),
    );
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
