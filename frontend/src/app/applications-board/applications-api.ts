import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { SessionState } from '../core/auth/session-state';
import { EXPECTED_SESSION_REVISION } from '../core/http/api-interceptor';
import { Application, ApplicationPage, ApplicationStatus } from './application.models';

const APPLICATIONS = '/api/applications';

@Injectable({ providedIn: 'root' })
export class ApplicationsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionState);

  list(page: number): Observable<ApplicationPage> {
    return this.http.get<ApplicationPage>(APPLICATIONS, { params: { page, size: 20 } });
  }

  get(id: number): Observable<Application> {
    return this.http.get<Application>(`${APPLICATIONS}/${id}`);
  }

  move(application: Application, status: ApplicationStatus): Observable<Application> {
    const job = application.job;
    const body = {
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
      version: application.version,
    };
    const context = new HttpContext().set(EXPECTED_SESSION_REVISION, this.session.revision());
    return this.http.put<Application>(`${APPLICATIONS}/${application.id}`, body, { context });
  }
}
