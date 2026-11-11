import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { SessionState } from '../core/auth/session-state';
import { EXPECTED_SESSION_REVISION } from '../core/http/api-interceptor';
import { ImportInput, ImportTask } from './import.models';

const IMPORTS = '/api/import-tasks';

@Injectable({ providedIn: 'root' })
export class ImportsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionState);

  create(input: ImportInput): Observable<ImportTask> {
    return this.http.post<ImportTask>(IMPORTS, input, { context: this.writeContext() });
  }

  get(id: number): Observable<ImportTask> {
    return this.http.get<ImportTask>(`${IMPORTS}/${id}`);
  }

  cancel(id: number): Observable<void> {
    return this.http.delete<void>(`${IMPORTS}/${id}`, { context: this.writeContext() });
  }

  private writeContext(): HttpContext {
    return new HttpContext().set(EXPECTED_SESSION_REVISION, this.session.revision());
  }
}
