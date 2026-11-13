import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { HelpPageComponent } from './help-page.component';

describe('Ajuda', () => {
  let fixture: ComponentFixture<HelpPageComponent>;
  let root: HTMLElement;

  function open(authenticated: boolean): void {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    if (authenticated) {
      TestBed.inject(SessionState).authenticate({
        id: 1,
        name: 'Pessoa Teste',
        email: 'conta@example.test',
        createdAt: '',
        photoUrl: null,
      });
    }
    fixture = TestBed.createComponent(HelpPageComponent);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  function text(): string {
    return root.textContent?.replace(/\s+/g, ' ') ?? '';
  }

  it('explica inscrição, revisão da sugestão, importação por texto e todas as etapas', () => {
    open(true);

    expect(text()).toContain('Salvar uma vaga não é se inscrever');
    expect(text()).toContain('Os dados são sugestões e podem conter erros.');
    expect(text()).toContain('copie a descrição da vaga e escolha "Descrição colada"');
    expect(Array.from(root.querySelectorAll('dt')).map((term) => term.textContent?.trim())).toEqual(
      [
        'Salva',
        'Candidatura realizada',
        'Triagem',
        'Entrevista',
        'Proposta',
        'Contratação',
        'Rejeitada',
        'Desistência',
      ],
    );
    expect(root.querySelector('a[href="/candidaturas/importar"]')).not.toBeNull();
  });

  it('não usa termos internos e oferece cadastro a quem não entrou', () => {
    open(false);

    expect(text()).not.toMatch(
      /\b(API|CSRF|token|worker|fila de tarefas|provedor|JSON|servidor)\b/i,
    );
    expect(root.querySelector('a[href="/cadastro"]')?.textContent?.trim()).toBe('Criar conta');
    expect(root.querySelector('a[href="/candidaturas/importar"]')).toBeNull();
  });

  it('não tem violações estruturais de acessibilidade', async () => {
    open(false);
    const result = await axe.run(root, {
      runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'],
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(result.violations.map((violation) => violation.id)).toEqual([]);
  });
});
