import { Injectable, signal } from '@angular/core';
import { ApplicationStatus, STATUS_LABELS } from './application.models';

export interface ApplicationFilters {
  readonly status: ApplicationStatus | null;
  readonly q: string;
}

export const NO_FILTERS: ApplicationFilters = { status: null, q: '' };
export const MAX_QUERY_LENGTH = 100;

/** Parâmetros da lista na URL: `etapa` e `busca`. */
export type ListQueryParams = Partial<Record<'etapa' | 'busca', string>>;

export function filtersFrom(params: { get(name: string): string | null }): ApplicationFilters {
  const status = params.get('etapa');
  return {
    status:
      status !== null && Object.hasOwn(STATUS_LABELS, status)
        ? (status as ApplicationStatus)
        : null,
    q: (params.get('busca') ?? '').trim().slice(0, MAX_QUERY_LENGTH),
  };
}

export function queryParamsOf(filters: ApplicationFilters): ListQueryParams {
  return {
    ...(filters.status ? { etapa: filters.status } : {}),
    ...(filters.q ? { busca: filters.q } : {}),
  };
}

/** Guarda os filtros da lista para a volta a partir dos detalhes. */
@Injectable({ providedIn: 'root' })
export class ApplicationListQuery {
  private readonly current = signal<ApplicationFilters>(NO_FILTERS);

  readonly params = () => queryParamsOf(this.current());

  remember(filters: ApplicationFilters): void {
    this.current.set(filters);
  }
}
