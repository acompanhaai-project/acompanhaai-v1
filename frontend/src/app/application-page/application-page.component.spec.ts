import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of } from 'rxjs';
import { appConfig } from '../app.config';
import { Application } from '../applications-board/application.models';
import { SessionState } from '../core/auth/session-state';
import { ApplicationPageComponent } from './application-page.component';

const endpoint = '/api/applications';
const application: Application = {
  id: 7,
  job: {
    id: 8,
    title: 'Pessoa desenvolvedora Java',
    companyName: 'Empresa Exemplo',
    sourceUrl: 'https://example.test/vaga',
    description: null,
    location: null,
    workMode: null,
    salary: null,
    salaryCurrency: null,
    salaryPeriod: null,
    hardSkills: ['Java'],
    softSkills: [],
  },
  status: 'SCREENING',
  appliedOn: '2026-10-31',
  notes: null,
  createdAt: '2026-10-31T12:00:00Z',
  updatedAt: '2026-11-01T12:00:00Z',
  version: 3,
};

describe('Cadastro e detalhes de candidaturas', () => {
  let fixture: ComponentFixture<ApplicationPageComponent>;
  let root: HTMLElement;
  let backend: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  function open(isNew = false): void {
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { routeConfig: { path: isNew ? 'candidaturas/nova' : 'candidaturas/:id' } },
            paramMap: of(convertToParamMap({ id: '7' })),
          },
        },
      ],
    });
    TestBed.inject(SessionState).authenticate({
      id: 1,
      name: 'Pessoa Teste',
      email: 'conta@example.test',
      createdAt: '',
      photoUrl: null,
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ApplicationPageComponent);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  afterEach(() => {
    try {
      backend.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function details(record = application): void {
    backend.expectOne(`${endpoint}/7`).flush(record);
    backend.expectOne(`${endpoint}/7/history`).flush([
      { id: 1, fromStatus: null, toStatus: 'APPLIED', changedAt: application.createdAt },
      { id: 2, fromStatus: 'APPLIED', toStatus: 'SCREENING', changedAt: application.updatedAt },
    ]);
    fixture.detectChanges();
  }

  function click(label: string): void {
    const button = Array.from(root.querySelectorAll('button')).find(
      (item) => item.textContent.trim() === label,
    );
    expect(button).toBeDefined();
    button?.click();
    fixture.detectChanges();
  }

  function field(name: string): HTMLInputElement {
    return root.querySelector<HTMLInputElement>(`[formControlName="${name}"]`)!;
  }

  function fill(name: string, value: string): void {
    const control = field(name);
    control.value = value;
    control.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  async function csrf(): Promise<void> {
    await Promise.resolve();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
  }

  it('cadastra vaga sem salário, explica link duplicado e mantém o formulário', async () => {
    open(true);
    click('Salvar candidatura');
    backend.expectNone(endpoint);

    fill('title', '  Pessoa desenvolvedora Java ');
    fill('hardSkills', ' Java\n\nSpring Boot');
    fill('sourceUrl', 'https://example.test/duplicada');
    click('Salvar candidatura');
    await csrf();
    const request = backend.expectOne({ method: 'POST', url: endpoint });
    expect(request.request.body).toMatchObject({
      job: {
        title: application.job.title,
        hardSkills: ['Java', 'Spring Boot'],
        salary: null,
        salaryCurrency: null,
        salaryPeriod: null,
      },
      status: 'APPLIED',
      appliedOn: null,
    });
    request.flush(
      { code: 'JOB_URL_ALREADY_EXISTS', detail: 'Já existe uma candidatura com esse link.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Já existe');
    expect(field('sourceUrl').value).toBe('https://example.test/duplicada');

    fill('sourceUrl', 'https://example.test/nova');
    click('Salvar candidatura');
    await Promise.resolve();
    backend.expectOne({ method: 'POST', url: endpoint }).flush(application);
    expect(navigate).toHaveBeenCalledWith(['/candidaturas', 7]);
  });

  it('exibe o histórico na ordem da API e o salário em BRL quando informado', () => {
    open();
    details({
      ...application,
      job: { ...application.job, salary: 9500.75, salaryCurrency: 'BRL', salaryPeriod: 'MONTH' },
    });
    const history = Array.from(root.querySelectorAll('ol li'), (entry) => entry.textContent);
    expect(history).toEqual([
      expect.stringContaining('Candidatura realizada'),
      expect.stringContaining('Triagem'),
    ]);
    expect(root.textContent).toContain('R$ 9.500,75 por mês');
  });

  it('envia a versão ao editar e preserva o rascunho diante de um conflito', async () => {
    open();
    details();
    expect(root.textContent).not.toContain('R$');
    click('Editar candidatura');
    fill('notes', 'Rascunho local');
    click('Salvar candidatura');
    await csrf();
    const request = backend.expectOne({ method: 'PUT', url: `${endpoint}/7` });
    expect(request.request.body).toMatchObject({ notes: 'Rascunho local', version: 3 });
    request.flush({}, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(
      'mudou desde que você a abriu',
    );
    expect(field('notes').value).toBe('Rascunho local');
    expect(root.querySelector<HTMLButtonElement>('[type="submit"]')?.disabled).toBe(true);

    click('Descartar rascunho e recarregar');
    details({ ...application, notes: 'Alteração remota', version: 4 });
    expect(root.textContent).toContain('Alteração remota');

    click('Editar candidatura');
    fill('notes', 'Alteração revisada');
    click('Salvar candidatura');
    await Promise.resolve();
    const retry = backend.expectOne({ method: 'PUT', url: `${endpoint}/7` });
    expect(retry.request.body).toMatchObject({ notes: 'Alteração revisada', version: 4 });
    const saved = { ...application, notes: 'Alteração revisada', version: 5 };
    retry.flush(saved);
    details(saved);
    expect(root.textContent).toContain('Candidatura atualizada.');
  });

  it('permite cancelar a exclusão e volta ao quadro após a confirmação', async () => {
    open();
    details();
    click('Excluir candidatura');
    click('Manter candidatura');
    backend.expectNone({ method: 'DELETE', url: `${endpoint}/7` });

    click('Excluir candidatura');
    click('Confirmar exclusão');
    await csrf();
    backend.expectOne({ method: 'DELETE', url: `${endpoint}/7` }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    expect(navigate).toHaveBeenCalledWith(['/candidaturas']);
  });
});
