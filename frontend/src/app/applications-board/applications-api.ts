import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { SessionState } from '../core/auth/session-state';
import { EXPECTED_SESSION_REVISION } from '../core/http/api-interceptor';
import {
  Application,
  ApplicationHistoryEntry,
  ApplicationInput,
  ApplicationPage,
  ApplicationStatus,
} from './application.models';
import { ApplicationFilters, NO_FILTERS } from './application-list-query';

const APPLICATIONS = '/api/applications';

@Injectable({ providedIn: 'root' })
export class ApplicationsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionState);

  list(page: number, filters: ApplicationFilters = NO_FILTERS): Observable<ApplicationPage> {
    return this.http.get<ApplicationPage>(APPLICATIONS, {
      params: {
        page,
        size: 20,
        ...(filters.status ? { status: filters.status } : {}),
        ...(filters.q ? { q: filters.q } : {}),
      },
    });
  }

  get(id: number): Observable<Application> {
    return this.http.get<Application>(`${APPLICATIONS}/${id}`);
  }

  history(id: number): Observable<readonly ApplicationHistoryEntry[]> {
    return this.http.get<readonly ApplicationHistoryEntry[]>(`${APPLICATIONS}/${id}/history`);
  }

  create(input: ApplicationInput): Observable<Application> {
    return this.http.post<Application>(APPLICATIONS, input, { context: this.writeContext() });
  }

  update(id: number, version: number, input: ApplicationInput): Observable<Application> {
    return this.http.put<Application>(
      `${APPLICATIONS}/${id}`,
      { ...input, version },
      { context: this.writeContext() },
    );
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${APPLICATIONS}/${id}`, { context: this.writeContext() });
  }

  move(application: Application, status: ApplicationStatus): Observable<Application> {
    const job = application.job;
    return this.update(application.id, application.version, {
      job: {
        title: job.title,
        companyName: job.companyName,
        sourceUrl: job.sourceUrl,
        description: job.description,
        location: job.location,
        workMode: job.workMode,
        salary: job.salary,
        salaryCurrency: job.salaryCurrency,
        salaryPeriod: job.salaryPeriod,
        hardSkills: job.hardSkills,
        softSkills: job.softSkills,
      },
      status,
      appliedOn: status === 'SAVED' ? null : application.appliedOn,
      notes: application.notes,
    });
  }

  private writeContext(): HttpContext {
    return new HttpContext().set(EXPECTED_SESSION_REVISION, this.session.revision());
  }
}
