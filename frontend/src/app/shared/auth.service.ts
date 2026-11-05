import { Injectable } from '@angular/core';
import { Observable, of, throwError, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';

export interface NovoUsuario {
  nome: string;
  email: string;
  senha: string;
}

/**
 * MOCK para demonstrar os estados da tela.
 *  - existente@exemplo.com → erro "e-mail já cadastrado"
 *  - rede@exemplo.com      → erro de conexão
 * Em produção, troque o corpo por: this.http.post('/api/cadastro', dados)
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  cadastrar(dados: NovoUsuario): Observable<{ id: string }> {
    return timer(1600).pipe(
      switchMap(() => {
        const email = dados.email.toLowerCase();
        if (email === 'existente@exemplo.com') {
          return throwError(() => new Error('EMAIL_EM_USO'));
        }
        if (email === 'rede@exemplo.com') {
          return throwError(() => new Error('REDE'));
        }
        return of({ id: crypto.randomUUID() });
      }),
    );
  }

  /**
   * MOCK: sempre "sucesso" (a API real deve responder igual exista ou não a conta,
   * para não revelar quais e-mails estão cadastrados). rede@exemplo.com simula falha de conexão.
   */
  solicitarRecuperacao(email: string): Observable<void> {
    return timer(1400).pipe(
      switchMap(() =>
        email.toLowerCase() === 'rede@exemplo.com'
          ? throwError(() => new Error('REDE'))
          : of(void 0),
      ),
    );
  }

  /** MOCK: o token "expirado" (ou vazio) é rejeitado. Qualquer outro é válido. */
  validarToken(token: string): Observable<void> {
    return timer(800).pipe(
      switchMap(() =>
        !token || token === 'expirado' ? throwError(() => new Error('TOKEN_INVALIDO')) : of(void 0),
      ),
    );
  }

  /** MOCK: o token "rede" simula falha de conexão. */
  redefinirSenha(token: string, senha: string): Observable<void> {
    return timer(1600).pipe(
      switchMap(() => {
        if (!senha) {
          return throwError(() => new Error('SENHA_INVALIDA'));
        }
        return token === 'rede' ? throwError(() => new Error('REDE')) : of(void 0);
      }),
    );
  }
}
