import { DatePipe, DecimalPipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, forkJoin, startWith, Subject, switchMap } from 'rxjs';
import {
  Application,
  ApplicationHistoryEntry,
  ApplicationInput,
  ApplicationStatus,
  Job,
  STATUS_LABELS,
} from '../applications-board/application.models';
import { ApplicationsApi } from '../applications-board/applications-api';
import { ApplicationListQuery } from '../applications-board/application-list-query';
import { ApiError, ApiFieldError } from '../core/http/api-error';

const WORK_MODES = { REMOTE: 'Remoto', HYBRID: 'Híbrido', ONSITE: 'Presencial' };
const SALARY_PERIODS = { HOUR: 'hora', DAY: 'dia', WEEK: 'semana', MONTH: 'mês', YEAR: 'ano' };

registerLocaleData(localePt);

function optional(value: string): string | null {
  return value.trim() || null;
}

function skills(value: string): string[] {
  return value
    .split('\n')
    .map((skill) => skill.trim())
    .filter(Boolean);
}

@Component({
  selector: 'app-application-page',
  imports: [RouterLink, ReactiveFormsModule, DatePipe, DecimalPipe],
  templateUrl: './application-page.component.html',
  styleUrl: './application-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApplicationPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ApplicationsApi);
  private readonly listQuery = inject(ApplicationListQuery);
  readonly listParams = this.listQuery.params;
  private readonly destroyRef = inject(DestroyRef);
  private readonly reload = new Subject<void>();

  protected readonly labels = STATUS_LABELS;
  protected readonly statuses = Object.keys(STATUS_LABELS) as ApplicationStatus[];
  protected readonly workModes = WORK_MODES;
  protected readonly workModeOptions = Object.keys(WORK_MODES) as NonNullable<Job['workMode']>[];
  protected readonly periods = SALARY_PERIODS;
  protected readonly periodOptions = Object.keys(SALARY_PERIODS) as NonNullable<
    Job['salaryPeriod']
  >[];
  protected readonly isNew = this.route.snapshot.routeConfig?.path === 'candidaturas/nova';
  protected readonly record = signal<Application | null>(null);
  protected readonly history = signal<readonly ApplicationHistoryEntry[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly busy = signal(false);
  protected readonly editing = signal(this.isNew);
  protected readonly confirmingDelete = signal(false);
  protected readonly conflict = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly fieldErrors = signal<readonly ApiFieldError[]>([]);

  protected readonly form = new FormGroup({
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
    status: new FormControl<ApplicationStatus>('APPLIED', { nonNullable: true }),
    appliedOn: new FormControl('', { nonNullable: true }),
    notes: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    if (this.isNew) {
      return;
    }

    this.route.paramMap
      .pipe(
        switchMap((params) => {
          const value = params.get('id') ?? '';
          this.busy.set(false);
          this.loadFailed.set(false);
          this.notice.set('');
          this.record.set(null);
          this.history.set([]);
          this.editing.set(false);
          this.confirmingDelete.set(false);
          this.clearError();

          const id = Number(value);
          if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(id)) {
            this.loading.set(false);
            this.error.set('Candidatura não encontrada.');
            return EMPTY;
          }

          return this.reload.pipe(
            startWith(undefined),
            switchMap(() => {
              this.clearError();
              this.loadFailed.set(false);
              this.loading.set(true);
              return forkJoin({ record: this.api.get(id), history: this.api.history(id) }).pipe(
                catchError((error: unknown) => {
                  this.loading.set(false);
                  this.loadFailed.set(true);
                  this.showError(error);
                  return EMPTY;
                }),
              );
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ record, history }) => {
        this.record.set(record);
        this.history.set(history);
        this.loading.set(false);
      });
  }

  protected retry(): void {
    this.reload.next();
  }

  protected isWebUrl(url: string): boolean {
    return /^https?:\/\//i.test(url);
  }

  protected startEdit(): void {
    const application = this.record();
    if (!application) {
      return;
    }

    const { job, status, appliedOn, notes } = application;
    this.form.reset({
      title: job.title,
      companyName: job.companyName ?? '',
      sourceUrl: job.sourceUrl ?? '',
      description: job.description ?? '',
      location: job.location ?? '',
      workMode: job.workMode,
      salary: job.salary,
      salaryPeriod: job.salaryPeriod,
      hardSkills: job.hardSkills.join('\n'),
      softSkills: job.softSkills.join('\n'),
      status,
      appliedOn: appliedOn ?? '',
      notes: notes ?? '',
    });
    this.clearError();
    this.notice.set('');
    this.editing.set(true);
  }

  protected cancelEdit(): void {
    const shouldReload = this.conflict();
    this.editing.set(false);
    this.clearError();
    if (shouldReload) {
      this.retry();
    }
  }

  protected save(): void {
    if (this.busy() || this.conflict()) {
      return;
    }

    this.clearError();
    const value = this.form.getRawValue();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set(
        this.form.controls.title.invalid
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
      status: value.status,
      appliedOn: value.status === 'SAVED' ? null : optional(value.appliedOn),
      notes: optional(value.notes),
    };
    const current = this.record();
    const request = current
      ? this.api.update(current.id, current.version, input)
      : this.api.create(input);

    this.busy.set(true);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        if (this.record() !== current) {
          return;
        }
        this.busy.set(false);
        if (this.isNew) {
          void this.router.navigate(['/candidaturas', saved.id]);
          return;
        }
        this.editing.set(false);
        this.record.set(saved);
        this.notice.set('Candidatura atualizada.');
        this.retry();
      },
      error: (error: unknown) => {
        if (this.record() !== current) {
          return;
        }
        this.busy.set(false);
        this.showError(error);
        if (
          current &&
          error instanceof ApiError &&
          error.status === 409 &&
          error.code !== 'JOB_URL_ALREADY_EXISTS'
        ) {
          this.conflict.set(true);
          this.error.set(
            'A candidatura mudou desde que você a abriu. Copie as alterações que quiser manter e recarregue os dados antes de editar novamente.',
          );
        }
      },
    });
  }

  protected confirmDelete(): void {
    this.clearError();
    this.notice.set('');
    this.confirmingDelete.set(true);
  }

  protected remove(): void {
    const application = this.record();
    if (!application || !this.confirmingDelete() || this.busy()) {
      return;
    }

    this.clearError();
    this.busy.set(true);
    this.api
      .delete(application.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          if (this.record() === application) {
            void this.router.navigate(['/candidaturas'], { queryParams: this.listParams() });
          }
        },
        error: (error: unknown) => {
          if (this.record() === application) {
            this.busy.set(false);
            this.showError(error);
          }
        },
      });
  }

  private clearError(): void {
    this.conflict.set(false);
    this.error.set('');
    this.fieldErrors.set([]);
  }

  private showError(error: unknown): void {
    if (!(error instanceof ApiError)) {
      this.error.set('Não foi possível concluir a operação. Tente novamente.');
      return;
    }

    this.fieldErrors.set(error.fieldErrors);
    this.error.set(error.message);
  }
}
