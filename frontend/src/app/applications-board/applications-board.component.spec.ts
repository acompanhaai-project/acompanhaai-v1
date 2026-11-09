import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { Application, ApplicationStatus } from './application.models';
import { ApplicationsBoardComponent } from './applications-board.component';

const job = {
  title: 'Pessoa desenvolvedora Java',
  companyName: 'Empresa Exemplo',
  sourceUrl: 'https://example.test/vaga',
  description: 'Descrição da vaga',
  location: 'Curitiba',
  workMode: 'HYBRID',
  salary: 9000,
  salaryCurrency: 'BRL',
  salaryPeriod: 'MONTH',
  hardSkills: ['Java'],
  softSkills: ['Comunicação'],
} as const;

function application(id: number, status: ApplicationStatus): Application {
  return {
    id,
    job: { ...job, id: id + 100 },
    status,
    appliedOn: status === 'SAVED' ? null : '2026-10-31',
    notes: 'Acompanhar retorno.',
    createdAt: '2026-10-31T12:00:00Z',
    updatedAt: '2026-10-31T12:00:00Z',
    version: 3,
  };
}

describe('Quadro de candidaturas', () => {
  let fixture: ComponentFixture<ApplicationsBoardComponent>;
  let root: HTMLElement;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    TestBed.inject(SessionState).authenticate({
      id: 1,
      name: 'Pessoa Teste',
      email: 'conta@example.test',
      createdAt: '',
      photoUrl: null,
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ApplicationsBoardComponent);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => backend.verify());

  function page(content: Application[], index = 0, total = content.length, totalPages = 1): void {
    backend.expectOne({ method: 'GET', url: `/api/applications?page=${index}&size=20` }).flush({
      content,
      page: index,
      size: 20,
      totalElements: total,
      totalPages,
    });
    fixture.detectChanges();
  }

  function card(id: number): HTMLElement {
    return root.querySelector<HTMLElement>(`#application-${id}`)!;
  }

  function button(text: string, within = root): HTMLButtonElement {
    return Array.from(within.querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === text,
    )!;
  }

  function choose(id: number, destination: ApplicationStatus): void {
    const select = card(id).querySelector('select')!;
    select.value = destination;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('separa as etapas e permite carregar outra página sem repetir cartões', () => {
    const first = application(1, 'APPLIED');
    page([first, application(2, 'SAVED'), application(3, 'REJECTED')], 0, 4, 2);

    expect(card(1).closest('section')?.dataset['lane']).toBe('applied');
    expect(card(2).closest('section')?.dataset['lane']).toBe('saved');
    expect(card(3).closest('section')?.dataset['lane']).toBe('finished');
    expect(card(3).textContent).toContain('Rejeitada');
    expect(root.querySelectorAll('.lanes:not(.secondary) .lane')).toHaveLength(4);

    button('Carregar mais candidaturas').click();
    page([first, application(4, 'OFFER')], 1, 4, 2);
    expect(root.querySelectorAll('article')).toHaveLength(4);
    expect(card(4).closest('section')?.dataset['lane']).toBe('offer');
    expect(button('Carregar mais candidaturas')).toBeUndefined();
  });

  it('mantém o destino em um conflito e usa os dados atuais no próximo envio', async () => {
    const first = application(1, 'APPLIED');
    page([first]);
    choose(1, 'INTERVIEW');
    button('Mover', card(1)).click();
    await Promise.resolve();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
    const outdated = backend.expectOne({ method: 'PUT', url: '/api/applications/1' });
    expect(outdated.request.headers.get('X-CSRF-TOKEN')).toBe('test-token');
    expect(outdated.request.body).toEqual({
      job,
      status: 'INTERVIEW',
      appliedOn: first.appliedOn,
      notes: first.notes,
      version: 3,
    });
    outdated.flush(
      { detail: 'O registro foi alterado. Recarregue antes de salvar.' },
      {
        status: 409,
        statusText: 'Conflict',
      },
    );
    fixture.detectChanges();
    expect(card(1).closest('section')?.dataset['lane']).toBe('applied');
    expect(card(1).querySelector('select')?.value).toBe('INTERVIEW');
    expect(card(1).querySelector('[role="alert"]')?.textContent).toContain(
      'O registro foi alterado',
    );
    expect(button('Mover', card(1)).disabled).toBe(true);
    expect(root.querySelector('.announcement')?.textContent).toBe('');

    button('Atualizar candidatura', card(1)).click();
    const fresh = { ...first, version: 4, status: 'SCREENING', notes: 'Atualizado em outra aba.' };
    backend.expectOne({ method: 'GET', url: '/api/applications/1' }).flush(fresh);
    fixture.detectChanges();
    expect(card(1).closest('section')?.dataset['lane']).toBe('screening');
    expect(card(1).querySelector('select')?.value).toBe('INTERVIEW');

    button('Mover', card(1)).click();
    await Promise.resolve();
    const current = backend.expectOne({ method: 'PUT', url: '/api/applications/1' });
    expect(current.request.body).toMatchObject({
      version: 4,
      notes: fresh.notes,
      status: 'INTERVIEW',
    });
    current.flush({ ...fresh, status: 'INTERVIEW', version: 5 });
    fixture.detectChanges();
    expect(card(1).closest('section')?.dataset['lane']).toBe('interview');
    expect(root.querySelector('.announcement')?.textContent).toContain(
      'Candidatura movida para Entrevista',
    );
  });

  it('avisa sobre a data e envia null ao mover para Salvas', async () => {
    const first = application(1, 'OFFER');
    page([first]);
    choose(1, 'SAVED');
    expect(card(1).textContent).toContain('Mover para Salvas remove a data da candidatura.');
    button('Mover', card(1)).click();
    await Promise.resolve();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
    const request = backend.expectOne({ method: 'PUT', url: '/api/applications/1' });
    expect(request.request.body).toMatchObject({ status: 'SAVED', appliedOn: null });
    request.flush({ ...first, status: 'SAVED', appliedOn: null, version: 4 });
    fixture.detectChanges();
    expect(card(1).closest('section')?.dataset['lane']).toBe('saved');
  });

  it('permite repetir uma consulta inicial que falhou e mostra o estado vazio', () => {
    backend.expectOne('/api/applications?page=0&size=20').flush(
      {},
      {
        status: 503,
        statusText: 'Service Unavailable',
      },
    );
    fixture.detectChanges();
    expect(root.querySelector('[role="alert"]')).not.toBeNull();
    button('Tentar novamente').click();
    page([], 0, 0, 0);
    expect(root.textContent).toContain('Você ainda não tem candidaturas cadastradas.');
  });
});
