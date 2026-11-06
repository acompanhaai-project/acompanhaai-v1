import { Injectable } from '@angular/core';
import { Observable, of, throwError, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';

@Injectable({ providedIn: 'root' })
export class AuthService {
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
