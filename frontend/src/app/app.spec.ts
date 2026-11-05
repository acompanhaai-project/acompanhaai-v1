import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { App } from './app';
import { routes } from './app.routes';
import { ErrorState } from './shared/ui/error-state';

describe('Navegação da base Angular', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
  });

  it('redireciona a raiz para o início e oferece navegação real para etapas', async () => {
    const harness = await RouterTestingHarness.create('/');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Cada candidatura',
    );

    const link = harness.routeNativeElement?.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/etapas');

    await harness.navigateByUrl('/etapas');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Entenda as etapas',
    );
  });

  it('apresenta as oito etapas, incluindo os três desfechos', async () => {
    const harness = await RouterTestingHarness.create('/etapas');
    const labels = Array.from(harness.routeNativeElement?.querySelectorAll('dt') ?? []).map(
      (element) => element.textContent?.trim(),
    );

    expect(labels).toEqual([
      'Salva',
      'Candidatura realizada',
      'Triagem',
      'Entrevista',
      'Proposta',
      'Contratado',
      'Reprovado',
      'Desistência',
    ]);
  });

  it('trata endereço desconhecido e permite voltar ao início', async () => {
    const harness = await RouterTestingHarness.create('/nao-existe');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Página não encontrada',
    );
    expect(harness.routeNativeElement?.querySelector('a')?.getAttribute('href')).toBe('/inicio');
  });

  it('associa erro de campo ao input e impede confirmação de título vazio', async () => {
    const harness = await RouterTestingHarness.create('/referencias');
    const page = harness.routeNativeElement as HTMLElement;
    page
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    harness.detectChanges();

    const input = page.querySelector('input');
    expect(input?.getAttribute('aria-invalid')).toBe('true');
    expect(input?.getAttribute('aria-describedby')).toContain('preview-title-error');
    expect(page.querySelector('#preview-title-error')?.textContent).toContain('Informe um título');
    expect(page.querySelector('.confirmation')).toBeNull();
  });

  it('rejeita título composto somente por espaços na prévia', async () => {
    const harness = await RouterTestingHarness.create('/referencias');
    const page = harness.routeNativeElement as HTMLElement;
    const input = page.querySelector('input') as HTMLInputElement;
    input.value = '   ';
    input.dispatchEvent(new Event('input'));
    page
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    harness.detectChanges();

    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(page.querySelector('.confirmation')).toBeNull();
  });

  it('alterna estados sem disparar uma operação de API', async () => {
    const harness = await RouterTestingHarness.create('/referencias');
    const page = harness.routeNativeElement as HTMLElement;
    const buttons = page.querySelectorAll<HTMLButtonElement>('.controls button');

    buttons[1].click();
    harness.detectChanges();
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Carregando candidaturas');

    buttons[2].click();
    harness.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain(
      'Não foi possível carregar',
    );
    expect(page.querySelector('app-loading-state')).toBeNull();
  });
});

describe('Recuperação explícita do estado de erro', () => {
  it('emite nova tentativa somente quando o usuário aciona o botão', () => {
    const fixture = TestBed.createComponent(ErrorState);
    fixture.componentRef.setInput('title', 'Falha ao carregar');
    fixture.componentRef.setInput('message', 'Tente novamente.');
    fixture.componentRef.setInput('retryLabel', 'Tentar novamente');
    const retry = vi.fn();
    fixture.componentInstance.retry.subscribe(retry);
    fixture.detectChanges();

    expect(retry).not.toHaveBeenCalled();
    (fixture.nativeElement as HTMLElement).querySelector('button')?.click();
    expect(retry).toHaveBeenCalledOnce();
  });
});

describe('Foco e link para pular conteúdo', () => {
  it('preserva a primeira navegação e foca o conteúdo sem recarregar a página', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const fixture = TestBed.createComponent(App);
    document.body.appendChild(fixture.nativeElement);

    try {
      fixture.detectChanges();
      const router = TestBed.inject(Router);
      await router.navigateByUrl('/inicio');
      await fixture.whenStable();
      const main = document.getElementById('main-content');
      expect(document.activeElement).not.toBe(main);

      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      (fixture.nativeElement as HTMLElement).querySelector('.skip-link')?.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(main);

      (document.activeElement as HTMLElement).blur();
      await router.navigateByUrl('/etapas');
      await fixture.whenStable();
      expect(document.activeElement).toBe(main);
    } finally {
      fixture.nativeElement.remove();
      fixture.destroy();
    }
  });
});
