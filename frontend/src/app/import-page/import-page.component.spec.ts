import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { ImportPageComponent, MAX_WATCH_MS } from './import-page.component';
import { ImportTask } from './import.models';

const endpoint = '/api/import-tasks';

function task(overrides: Partial<ImportTask> = {}): ImportTask {
  return {
    id: 7,
    inputType: 'URL',
    status: 'PENDING',
    expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
    ...overrides,
  };
}

describe('Importação de vagas', () => {
  let fixture: ComponentFixture<ImportPageComponent>;
  let root: HTMLElement;
  let backend: HttpTestingController;
  let params: BehaviorSubject<ParamMap>;
  let navigate: ReturnType<typeof vi.spyOn>;

  function open(id: string | null = null): void {
    params = new BehaviorSubject(convertToParamMap(id === null ? {} : { id }));
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
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
    fixture = TestBed.createComponent(ImportPageComponent);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
    try {
      backend.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function text(): string {
    return root.textContent?.replace(/\s+/g, ' ') ?? '';
  }

  function button(label: string): HTMLButtonElement {
    return Array.from(root.querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    )!;
  }

  function type(selector: string, value: string): void {
    const field = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function submit(): void {
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  async function csrf(): Promise<void> {
    await Promise.resolve();
    for (const request of backend.match('/api/auth/csrf')) {
      request.flush({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
    }
    await Promise.resolve();
  }

  function field<T extends HTMLElement = HTMLInputElement>(name: string): T {
    return root.querySelector<T>(`[formcontrolname=${name}]`)!;
  }

  function poll(response: ImportTask): void {
    backend.expectOne({ method: 'GET', url: `${endpoint}/7` }).flush(response);
    fixture.detectChanges();
  }

  it('envia o link e abre o acompanhamento da tarefa criada', async () => {
    open();
    type('input[type=url]', '  https://vagas.example.test/java  ');
    submit();
    await csrf();

    const request = backend.expectOne({ method: 'POST', url: endpoint });
    expect(request.request.body).toEqual({
      inputType: 'URL',
      url: 'https://vagas.example.test/java',
    });
    request.flush(task(), { status: 202, statusText: 'Accepted' });

    expect(navigate).toHaveBeenCalledWith(['/candidaturas/importar', 7], { replaceUrl: false });
  });

  it('envia a descrição colada e evita envio vazio', async () => {
    open();
    root.querySelector<HTMLInputElement>('input[value=TEXT]')!.click();
    fixture.detectChanges();
    submit();
    expect(text()).toContain('Cole a descrição da vaga.');

    type('textarea', 'Pessoa desenvolvedora Java. Remoto.');
    submit();
    await csrf();
    expect(backend.expectOne(endpoint).request.body).toEqual({
      inputType: 'TEXT',
      text: 'Pessoa desenvolvedora Java. Remoto.',
    });
  });

  it('distingue cota esgotada, limite de ativas e sobrecarga', async () => {
    open();
    type('input[type=url]', 'https://vagas.example.test/java');

    submit();
    await csrf();
    backend
      .expectOne(endpoint)
      .flush(
        { status: 429, code: 'IMPORT_QUOTA_EXCEEDED', detail: 'Limite atingido.' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '600' } },
      );
    fixture.detectChanges();
    expect(text()).toContain('Tente novamente em 10 minutos');

    submit();
    await csrf();
    backend.expectOne(endpoint).flush(
      {
        status: 429,
        code: 'IMPORT_ACTIVE_LIMIT',
        detail: 'Aguarde o processamento das importações em andamento.',
      },
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '30' } },
    );
    fixture.detectChanges();
    expect(text()).toContain('Aguarde o processamento das importações em andamento.');

    submit();
    await csrf();
    backend
      .expectOne(endpoint)
      .flush(
        { status: 503, code: 'IMPORT_OVERLOADED', detail: 'Sobrecarga.' },
        { status: 503, statusText: 'Service Unavailable', headers: { 'Retry-After': '60' } },
      );
    fixture.detectChanges();
    expect(text()).toContain('A importação está sobrecarregada no momento.');
    expect(root.querySelector('[role=alert] a')?.getAttribute('href')).toBe('/candidaturas/nova');
  });

  it('consulta com intervalo crescente até a proposta ficar pronta', () => {
    vi.useFakeTimers();
    open('7');
    poll(task());
    expect(text()).toContain('Na fila de importação');

    vi.advanceTimersByTime(999);
    backend.expectNone(`${endpoint}/7`);
    vi.advanceTimersByTime(1);
    poll(task({ status: 'PROCESSING' }));
    expect(text()).toContain('Lendo a vaga');

    vi.advanceTimersByTime(1999);
    backend.expectNone(`${endpoint}/7`);
    vi.advanceTimersByTime(1);
    poll(
      task({
        status: 'READY_FOR_REVIEW',
        result: {
          title: 'Pessoa desenvolvedora Java',
          companyName: 'Empresa Exemplo',
          workMode: 'REMOTE',
          salary: 8500,
          salaryCurrency: 'BRL',
          salaryPeriod: 'MONTH',
          description: 'Atuar com Spring.',
        },
      }),
    );

    expect(text()).toContain('Revise os dados da vaga');
    expect(field('title').value).toBe('Pessoa desenvolvedora Java');
    vi.advanceTimersByTime(60_000);
    backend.expectNone(`${endpoint}/7`);
  });

  it('limita o intervalo a 10 segundos e pausa após o tempo máximo', () => {
    vi.useFakeTimers();
    open('7');
    poll(task());
    let elapsed = 0;
    for (const delay of [1000, 2000, 4000, 8000, 10000, 10000]) {
      vi.advanceTimersByTime(delay);
      elapsed += delay;
      poll(task({ status: 'PROCESSING' }));
    }

    while (elapsed < MAX_WATCH_MS) {
      vi.advanceTimersByTime(10000);
      elapsed += 10000;
      poll(task({ status: 'PROCESSING' }));
    }
    vi.advanceTimersByTime(30_000);
    backend.expectNone(`${endpoint}/7`);
    expect(text()).toContain('A importação está demorando mais que o esperado.');

    button('Continuar acompanhando').click();
    fixture.detectChanges();
    poll(task({ status: 'PROCESSING' }));
    expect(text()).not.toContain('demorando mais que o esperado');
  });

  it('para de consultar ao sair da tela', () => {
    vi.useFakeTimers();
    open('7');
    poll(task());

    fixture.destroy();
    vi.advanceTimersByTime(30_000);
    backend.expectNone(`${endpoint}/7`);
  });

  it('para ao atingir o prazo da tarefa', () => {
    open('7');
    poll(task({ expiresAt: new Date(Date.now() - 1000).toISOString() }));

    expect(text()).toContain('O prazo desta importação terminou.');
  });

  it('distingue bloqueio do site e conteúdo ausente e permite colar a descrição', async () => {
    open('7');
    poll(
      task({
        status: 'NEEDS_INPUT',
        failure: {
          code: 'SOURCE_REQUIRES_LOGIN',
          message: 'A página pede login. Cole a descrição para continuar.',
        },
      }),
    );
    expect(text()).toContain('O site não permitiu a leitura da vaga');
    expect(text()).toContain('A página pede login.');

    params.next(convertToParamMap({ id: '7' }));
    fixture.detectChanges();
    poll(
      task({
        status: 'NEEDS_INPUT',
        failure: {
          code: 'SOURCE_NO_USABLE_CONTENT',
          message: 'Não foi possível obter a descrição da página. Cole o texto para continuar.',
        },
      }),
    );
    expect(text()).toContain('Não encontramos a descrição na página');

    type('textarea', 'Descrição completa da vaga.');
    submit();
    await csrf();
    const request = backend.expectOne({ method: 'POST', url: endpoint });
    expect(request.request.body).toEqual({
      inputType: 'TEXT',
      text: 'Descrição completa da vaga.',
    });
    request.flush(task({ id: 8, inputType: 'TEXT' }), { status: 202, statusText: 'Accepted' });
    expect(navigate).toHaveBeenCalledWith(['/candidaturas/importar', 8], { replaceUrl: true });
  });

  it('cancela a importação em andamento', async () => {
    open('7');
    poll(task({ status: 'PROCESSING' }));

    button('Cancelar importação').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(text()).toContain('Descartar esta importação?');
    expect(document.activeElement?.id).toBe('discard-title');
    backend.expectNone(`${endpoint}/7`);
    button('Manter importação').click();
    fixture.detectChanges();
    expect(text()).not.toContain('Descartar esta importação?');

    button('Cancelar importação').click();
    fixture.detectChanges();
    button('Confirmar descarte').click();
    await csrf();
    backend.expectOne({ method: 'DELETE', url: `${endpoint}/7` }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });

    expect(navigate).toHaveBeenCalledWith(['/candidaturas/importar'], {
      replaceUrl: true,
      state: { notice: 'Importação cancelada.' },
    });
  });

  it('informa tarefa inexistente ou expirada e permite tentar de novo após falha de rede', () => {
    open('7');
    backend.expectOne(`${endpoint}/7`).flush(
      { status: 404, detail: 'Tarefa de importação não encontrada.' },
      {
        status: 404,
        statusText: 'Not Found',
      },
    );
    fixture.detectChanges();
    expect(text()).toContain('A importação não foi encontrada ou já expirou.');

    params.next(convertToParamMap({ id: '7' }));
    fixture.detectChanges();
    backend.expectOne(`${endpoint}/7`).error(new ProgressEvent('error'));
    fixture.detectChanges();
    button('Tentar novamente').click();
    fixture.detectChanges();
    poll(task({ status: 'COMPLETED', jobId: 30 }));
    expect(text()).toContain('Vaga salva');
  });

  it('rejeita identificadores inválidos sem consultar a API', () => {
    open('abc');

    expect(text()).toContain('Importação indisponível');
  });
  describe('revisão', () => {
    const ready = task({
      status: 'READY_FOR_REVIEW',
      result: {
        title: 'Pessoa desenvolvedora Java',
        companyName: 'Empresa Exemplo',
        workMode: 'REMOTE',
        salary: 8500,
        salaryCurrency: 'BRL',
        salaryPeriod: 'MONTH',
        description: 'Atuar com Spring.',
        evidence: {
          title: { source: 'STRUCTURED_DATA' },
          workMode: { source: 'TEXT', excerpt: 'Trabalho 100% remoto' },
        },
      },
    });
    const saved = { id: 40, job: { id: 30 } };

    function confirmRequest() {
      return backend.expectOne({ method: 'POST', url: `${endpoint}/7/confirm` });
    }

    it('preenche os campos encontrados e indica os ausentes', () => {
      open('7');
      poll(ready);

      expect(field('title').value).toBe('Pessoa desenvolvedora Java');
      expect(field('companyName').value).toBe('Empresa Exemplo');
      expect(field('salary').value).toBe('8500');
      expect(field<HTMLTextAreaElement>('description').value).toBe('Atuar com Spring.');
      const missing = Array.from(root.querySelectorAll('.missing')).map(
        (tag) => `${tag.previousElementSibling?.textContent?.trim()}: ${tag.textContent?.trim()}`,
      );
      expect(missing).toEqual([
        'Localização: Não encontrada na vaga',
        'Habilidades técnicas: Não encontradas na vaga',
        'Habilidades comportamentais: Não encontradas na vaga',
      ]);
      expect(text()).toContain('Se ficar vazio, a vaga é salva com o link importado.');
      const origins = Array.from(root.querySelectorAll('.evidence:not(.field-hint)')).map((node) =>
        node.textContent?.trim(),
      );
      expect(origins).toEqual([
        'Origem: dados estruturados da página.',
        'Trecho da vaga: “Trabalho 100% remoto”',
      ]);
    });

    it('salva como interesse com as correções e abre a candidatura', async () => {
      open('7');
      poll(ready);
      type('[formcontrolname=companyName]', '  Empresa Corrigida ');
      type('[formcontrolname=hardSkills]', 'Java\n\n Spring ');

      submit();
      submit();
      await csrf();
      const request = confirmRequest();
      expect(request.request.body).toEqual({
        job: {
          title: 'Pessoa desenvolvedora Java',
          companyName: 'Empresa Corrigida',
          sourceUrl: null,
          description: 'Atuar com Spring.',
          location: null,
          workMode: 'REMOTE',
          salary: 8500,
          salaryCurrency: 'BRL',
          salaryPeriod: 'MONTH',
          hardSkills: ['Java', 'Spring'],
          softSkills: [],
        },
        status: 'SAVED',
        appliedOn: null,
        notes: null,
      });
      request.flush(saved, { status: 201, statusText: 'Created' });

      expect(navigate).toHaveBeenCalledWith(['/candidaturas', 40], { replaceUrl: true });
    });

    it('registra a inscrição com a data escolhida', async () => {
      open('7');
      poll(ready);
      root.querySelector<HTMLInputElement>('input[value=APPLIED]')!.click();
      fixture.detectChanges();
      type('[formcontrolname=appliedOn]', '2026-11-10');

      submit();
      await csrf();
      const request = confirmRequest();
      expect(request.request.body).toMatchObject({ status: 'APPLIED', appliedOn: '2026-11-10' });
      request.flush(saved, { status: 200, statusText: 'OK' });
      expect(navigate).toHaveBeenCalledWith(['/candidaturas', 40], { replaceUrl: true });
    });

    it('mantém as correções quando o link já foi cadastrado', async () => {
      open('7');
      poll(ready);
      type('[formcontrolname=title]', 'Título corrigido');

      submit();
      await csrf();
      confirmRequest().flush(
        { status: 409, code: 'JOB_URL_ALREADY_EXISTS', detail: 'Duplicada.' },
        { status: 409, statusText: 'Conflict' },
      );
      fixture.detectChanges();

      expect(text()).toContain('Você já cadastrou uma vaga com esse link.');
      expect(field('title').value).toBe('Título corrigido');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('separa nome, ausência e evidência para leitores de tela', () => {
      open('7');
      poll(ready);

      const location = field('location');
      expect(location.getAttribute('aria-labelledby')).toBe('location-label');
      expect(root.querySelector('#location-label')?.textContent?.trim()).toBe('Localização');
      expect(location.getAttribute('aria-describedby')).toBe('location-missing');
      const mode = field<HTMLSelectElement>('workMode');
      expect(mode.getAttribute('aria-describedby')).toBe('workMode-evidence');
      expect(root.querySelector('#workMode-evidence')?.textContent).toContain(
        'Trabalho 100% remoto',
      );
      expect(field('title').getAttribute('aria-describedby')).toBe('title-evidence');
      expect(field('companyName').getAttribute('aria-describedby')).toBeNull();
      expect(field<HTMLTextAreaElement>('hardSkills').getAttribute('aria-describedby')).toBe(
        'skill-hint hardSkills-missing',
      );
      expect(field('salary').getAttribute('aria-describedby')).toBe('salary-hint');
      expect(field('sourceUrl').getAttribute('aria-describedby')).toBe('link-hint');
    });

    it('anuncia os estados e leva o foco ao título de cada etapa', async () => {
      open('7');
      const announcer = root.querySelector('p.sr-only[role=status]')!;
      expect(announcer.textContent?.trim()).toBe('');

      poll(task({ status: 'PENDING' }));
      await fixture.whenStable();
      expect(announcer.textContent?.trim()).toBe('Importação na fila.');
      expect(document.activeElement?.id).toBe('active-title');
      fixture.destroy();
      TestBed.resetTestingModule();

      open('7');
      poll(ready);
      await fixture.whenStable();
      expect(root.querySelector('p.sr-only[role=status]')?.textContent?.trim()).toBe(
        'Dados prontos para revisão.',
      );
      expect(document.activeElement?.id).toBe('ready-title');
    });

    it('não confunde salário ausente com zero', async () => {
      open('7');
      poll(
        task({ status: 'READY_FOR_REVIEW', result: { title: 'Analista', description: 'Texto' } }),
      );

      expect(field('salary').value).toBe('');
      expect(root.querySelector('#salary-missing')?.textContent?.trim()).toBe(
        'Não encontrado na vaga',
      );
      type('[formcontrolname=salary]', '0');
      const period = field<HTMLSelectElement>('salaryPeriod');
      period.value = period.options[1].value;
      period.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      submit();
      expect(text()).toContain('Informe um salário maior que zero ou deixe o campo em branco.');
      backend.expectNone(`${endpoint}/7/confirm`);

      type('[formcontrolname=salary]', '');
      submit();
      await csrf();
      const request = confirmRequest();
      expect(request.request.body.job).toEqual(
        expect.objectContaining({ salary: null, salaryCurrency: null, salaryPeriod: null }),
      );
      request.flush(saved, { status: 201, statusText: 'Created' });
    });

    it('mostra o texto da página como texto, sem interpretar marcação', () => {
      const hostile = '<img src=x onerror="window.alert(1)">';
      open('7');
      poll(
        task({
          status: 'READY_FOR_REVIEW',
          result: {
            title: `Analista ${hostile}`,
            description: `<script>window.alert(2)</script> Descrição`,
            evidence: { title: { source: 'TEXT', excerpt: `Analista ${hostile}` } },
          },
        }),
      );

      expect(root.querySelector('form img, form script')).toBeNull();
      expect(field('title').value).toBe(`Analista ${hostile}`);
      expect(root.querySelector('#title-evidence')?.textContent).toContain(hostile);
      expect(field<HTMLTextAreaElement>('description').value).toContain('<script>');
    });

    it('explica que a sugestão exige revisão e que salvar não é se inscrever', () => {
      open('7');
      poll(ready);

      expect(text()).toContain(
        'foram sugeridos automaticamente a partir da vaga e podem conter erros',
      );
      expect(text()).toContain('Salvar não envia candidatura para a empresa.');
    });

    it('pede confirmação antes de descartar a revisão', async () => {
      open('7');
      poll(ready);

      button('Descartar importação').click();
      fixture.detectChanges();
      backend.expectNone(`${endpoint}/7`);
      expect(root.querySelector('[role=group][aria-labelledby=discard-title]')).not.toBeNull();
      button('Confirmar descarte').click();
      await csrf();
      backend.expectOne({ method: 'DELETE', url: `${endpoint}/7` }).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      expect(navigate).toHaveBeenCalledWith(['/candidaturas/importar'], {
        replaceUrl: true,
        state: { notice: 'Importação cancelada.' },
      });
    });

    it('exige título e período do salário antes de enviar', () => {
      open('7');
      poll(ready);
      type('[formcontrolname=title]', '   ');
      submit();
      expect(text()).toContain('Preencha o título da vaga');

      type('[formcontrolname=title]', 'Analista');
      const period = field<HTMLSelectElement>('salaryPeriod');
      period.value = period.options[0].value;
      period.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      submit();
      expect(text()).toContain('Selecione o período do salário.');
      backend.expectNone(`${endpoint}/7/confirm`);
    });
  });
});
