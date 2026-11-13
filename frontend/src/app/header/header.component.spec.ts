import { ComponentFixture, TestBed } from '@angular/core/testing';
import { appConfig } from '../app.config';
import { SessionState } from '../core/auth/session-state';
import { HeaderComponent } from './header.component';

describe('Cabeçalho', () => {
  let fixture: ComponentFixture<HeaderComponent>;
  let root: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: appConfig.providers });
    fixture = TestBed.createComponent(HeaderComponent);
    root = fixture.nativeElement as HTMLElement;
  });

  function links(): string[][] {
    return Array.from(root.querySelectorAll<HTMLAnchorElement>('a')).map((link) => [
      link.textContent?.trim() ?? '',
      link.getAttribute('href') ?? '',
    ]);
  }

  it('mostra só destinos reais para quem ainda não entrou', () => {
    fixture.detectChanges();

    expect(links()).toEqual([
      ['Acompanha Aí', '/entrar'],
      ['Ajuda', '/ajuda'],
      ['Entrar', '/entrar'],
    ]);
  });

  it('leva o logo ao quadro e mostra a navegação da conta após o login', () => {
    TestBed.inject(SessionState).authenticate({
      id: 1,
      name: 'Pessoa Teste',
      email: 'conta@example.test',
      createdAt: '',
      photoUrl: null,
    });
    fixture.detectChanges();

    expect(links()).toEqual([
      ['Acompanha Aí', '/candidaturas'],
      ['Candidaturas', '/candidaturas'],
      ['Importar vaga', '/candidaturas/importar'],
      ['Ajuda', '/ajuda'],
      ['Minha conta', '/conta'],
    ]);
    expect(root.querySelector('a[href="#"]')).toBeNull();
  });
});
