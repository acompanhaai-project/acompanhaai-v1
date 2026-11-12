import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { Application, ApplicationStatus } from './application.models';
import { ApplicationListQuery } from './application-list-query';
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
  let navigate: ReturnType<typeof vi.spyOn>;

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
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
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

  function list(query: string): ReturnType<HttpTestingController['expectOne']> {
    return backend.expectOne({ method: 'GET', url: `/api/applications?page=0&size=20${query}` });
  }

  function respond(
    request: ReturnType<HttpTestingController['expectOne']>,
    content: Application[],
    total = content.length,
  ): void {
    request.flush({ content, page: 0, size: 20, totalElements: total, totalPages: total ? 1 : 0 });
    fixture.detectChanges();
  }

  function search(text: string): void {
    const input = root.querySelector<HTMLInputElement>('#filter-q')!;
    input.value = text;
    root.querySelector<HTMLFormElement>('form.filters')!.requestSubmit();
    fixture.detectChanges();
  }

  function stage(value: string): void {
    const select = root.querySelector<HTMLSelectElement>('#filter-status')!;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('combina pesquisa e etapa na consulta e guarda os filtros na URL', () => {
    page([application(1, 'APPLIED'), application(2, 'INTERVIEW')]);

    search('  java  ');
    respond(list('&q=java'), [application(1, 'APPLIED'), application(2, 'INTERVIEW')]);
    expect(navigate).toHaveBeenLastCalledWith([], {
      relativeTo: TestBed.inject(ActivatedRoute),
      queryParams: { busca: 'java' },
      replaceUrl: true,
    });
    expect(root.querySelector('.announcement')?.textContent).toBe('2 candidaturas encontradas.');

    stage('INTERVIEW');
    respond(list('&status=INTERVIEW&q=java'), [application(2, 'INTERVIEW')]);
    expect(navigate).toHaveBeenLastCalledWith([], {
      relativeTo: TestBed.inject(ActivatedRoute),
      queryParams: { etapa: 'INTERVIEW', busca: 'java' },
      replaceUrl: true,
    });
    expect(
      Array.from(root.querySelectorAll<HTMLElement>('.lane')).map((lane) => lane.dataset['lane']),
    ).toEqual(['interview']);
    expect(root.querySelectorAll('article')).toHaveLength(1);
    expect(root.querySelector('.count')?.textContent).toContain('Mostrando 1 de 1');
    expect(TestBed.inject(ApplicationListQuery).params()).toEqual({
      etapa: 'INTERVIEW',
      busca: 'java',
    });
  });

  it('pagina dentro do filtro e descarta a resposta de um filtro anterior', () => {
    page([application(1, 'APPLIED')], 0, 30, 2);

    stage('SAVED');
    const outdated = list('&status=SAVED');
    search('kotlin');
    expect(outdated.cancelled).toBe(true);
    list('&status=SAVED&q=kotlin').flush({
      content: [application(5, 'SAVED')],
      page: 0,
      size: 20,
      totalElements: 21,
      totalPages: 2,
    });
    fixture.detectChanges();
    expect(root.querySelectorAll('article')).toHaveLength(1);

    button('Carregar mais candidaturas').click();
    backend
      .expectOne({ method: 'GET', url: '/api/applications?page=1&size=20&status=SAVED&q=kotlin' })
      .flush({
        content: [application(6, 'SAVED')],
        page: 1,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      });
    fixture.detectChanges();
    expect(root.querySelectorAll('article')).toHaveLength(2);
  });

  it('distingue lista vazia de nenhum resultado e limpa os filtros', () => {
    page([application(1, 'APPLIED')]);
    expect(button('Limpar filtros')).toBeUndefined();

    search('inexistente');
    respond(list('&q=inexistente'), []);
    expect(root.textContent).toContain('Nenhuma candidatura encontrada com esses filtros.');
    expect(root.textContent).not.toContain('Você ainda não tem candidaturas cadastradas.');
    expect(root.querySelectorAll('.lane')).toHaveLength(0);
    expect(root.querySelector('.announcement')?.textContent).toBe('0 candidaturas encontradas.');

    button('Limpar filtros').click();
    fixture.detectChanges();
    respond(list(''), [application(1, 'APPLIED')]);
    expect(root.querySelector<HTMLInputElement>('#filter-q')?.value).toBe('');
    expect(root.querySelector<HTMLSelectElement>('#filter-status')?.value).toBe('');
    expect(root.querySelectorAll('article')).toHaveLength(1);
    expect(navigate).toHaveBeenLastCalledWith([], {
      relativeTo: TestBed.inject(ActivatedRoute),
      queryParams: {},
      replaceUrl: true,
    });
  });

  it('não repete a consulta quando os filtros não mudam', () => {
    page([application(1, 'APPLIED')]);
    search('   ');
    stage('');
    backend.expectNone((request) => request.url === '/api/applications');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('tira da lista filtrada a candidatura movida para outra etapa', async () => {
    page([application(1, 'APPLIED')]);
    stage('APPLIED');
    respond(list('&status=APPLIED'), [application(1, 'APPLIED'), application(2, 'APPLIED')]);

    choose(1, 'INTERVIEW');
    button('Mover', card(1)).click();
    await Promise.resolve();
    backend.expectOne('/api/auth/csrf').flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
    backend
      .expectOne({ method: 'PUT', url: '/api/applications/1' })
      .flush({ ...application(1, 'INTERVIEW'), version: 4 });
    fixture.detectChanges();

    expect(card(1)).toBeNull();
    expect(root.querySelectorAll('article')).toHaveLength(1);
    expect(root.querySelector('.count')?.textContent).toContain('Mostrando 1 de 1');
    expect(root.querySelector('.announcement')?.textContent).toContain(
      'Ela não aparece com o filtro de etapa atual.',
    );
  });
  it('nomeia controles e colunas para leitores de tela', () => {
    page([application(1, 'APPLIED'), application(2, 'APPLIED'), application(3, 'INTERVIEW')]);

    const applied = root.querySelector<HTMLElement>('[data-lane="applied"]')!;
    expect(applied.querySelector('h3')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Candidatura realizada (2 candidaturas)',
    );
    expect(applied.querySelector('.lane-count')?.getAttribute('aria-hidden')).toBe('true');
    expect(
      root.querySelector('[data-lane="interview"] h3')?.textContent?.replace(/\s+/g, ' ').trim(),
    ).toBe('Entrevista (1 candidatura)');

    const select = root.querySelector<HTMLSelectElement>('#move-to-1')!;
    const label = root.querySelector(`label[for="move-to-1"]`)!.textContent!.trim();
    expect(select.getAttribute('aria-label')).toContain(label);
    expect(select.getAttribute('aria-label')).toContain(application(1, 'APPLIED').job.title);
    expect(button('Mover', card(1)).getAttribute('aria-describedby')).toBe('application-title-1');

    const actions = root.querySelector('nav.actions')!;
    expect(actions.getAttribute('aria-label')).toBe('Adicionar candidatura');
    expect(
      Array.from(actions.querySelectorAll('a')).map((link) => link.textContent?.trim()),
    ).toEqual(['Nova candidatura', 'Importar vaga']);
  });

  it('não tem violações estruturais de acessibilidade no quadro', async () => {
    page([application(1, 'SAVED'), application(2, 'APPLIED'), application(3, 'REJECTED')]);
    choose(1, 'INTERVIEW');

    const result = await axe.run(root, {
      runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'],
      rules: { 'color-contrast': { enabled: false } },
    });

    expect(result.violations.map((violation) => violation.id)).toEqual([]);
  });
});

describe('Quadro de candidaturas com filtros na URL', () => {
  it('restaura etapa e pesquisa ao voltar dos detalhes e ignora valores inválidos', () => {
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap({ etapa: 'OFFER', busca: ' Exemplo ' }) },
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
    const backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ApplicationsBoardComponent);
    const root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    backend
      .expectOne('/api/applications?page=0&size=20&status=OFFER&q=Exemplo')
      .flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#filter-q')?.value).toBe('Exemplo');
    expect(root.querySelector<HTMLSelectElement>('#filter-status')?.value).toBe('OFFER');
    expect(root.textContent).toContain('Nenhuma candidatura encontrada com esses filtros.');
    backend.verify();
  });

  it('descarta etapa desconhecida vinda da URL', () => {
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ etapa: 'DROP TABLE' }) } },
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
    const backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ApplicationsBoardComponent);
    fixture.detectChanges();
    backend.expectOne('/api/applications?page=0&size=20');
    backend.verify();
  });
});
