import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ApiError, toApiError } from './api-error';

describe('Normalização dos erros HTTP', () => {
  it.each([
    [401, { error: 'Autenticação necessária.' }, 'authentication'],
    [401, { detail: 'Credenciais inválidas.' }, 'authentication'],
    [403, { error: 'Acesso negado ou token CSRF inválido.' }, 'forbidden'],
    [409, { detail: 'Conflito de versão.' }, 'conflict'],
    [422, { detail: 'Dados inválidos.' }, 'validation'],
  ])('interpreta o status %i com os dois formatos da API', (status, body, kind) => {
    const error = toApiError(new HttpErrorResponse({ status, error: body }));
    expect(error).toBeInstanceOf(ApiError);
    expect(error.kind).toBe(kind);
    expect(error.message).toBe('detail' in body ? body.detail : body.error);
  });

  it('preserva o código de duplicidade para orientar a correção', () => {
    const error = toApiError(
      new HttpErrorResponse({
        status: 409,
        error: {
          detail: 'Esta vaga já foi cadastrada com esse link.',
          code: 'JOB_URL_ALREADY_EXISTS',
        },
      }),
    );
    expect(error.code).toBe('JOB_URL_ALREADY_EXISTS');
    expect(error.kind).toBe('conflict');
  });

  it('mantém apenas mensagens de campo válidas em 422', () => {
    const error = toApiError(
      new HttpErrorResponse({
        status: 422,
        error: {
          detail: 'Dados inválidos.',
          errors: [
            { field: 'job.title', message: 'Informe o título.' },
            { field: 123, message: 'Inválido' },
            null,
          ],
        },
      }),
    );
    expect(error.fieldErrors).toEqual([{ field: 'job.title', message: 'Informe o título.' }]);
  });

  it('aceita 422 com detail sem exigir lista de campos', () => {
    const error = toApiError(
      new HttpErrorResponse({ status: 422, error: { detail: 'Salário exige moeda e período.' } }),
    );
    expect(error.message).toBe('Salário exige moeda e período.');
    expect(error.fieldErrors).toEqual([]);
  });

  it('lê Retry-After em segundos sem agendar repetição', () => {
    const error = toApiError(
      new HttpErrorResponse({ status: 429, headers: new HttpHeaders({ 'Retry-After': '60' }) }),
    );
    expect(error.retryAfterSeconds).toBe(60);
    expect(error.kind).toBe('rate-limit');
  });

  it('ignora Retry-After inválido', () => {
    expect(
      toApiError(
        new HttpErrorResponse({
          status: 429,
          headers: new HttpHeaders({ 'Retry-After': 'amanhã' }),
        }),
      ).retryAfterSeconds,
    ).toBeNull();
  });

  it('não apresenta HTML de proxy ou detalhes internos de erro 500', () => {
    const error = toApiError(
      new HttpErrorResponse({ status: 500, error: { detail: 'Detalhe interno do servidor' } }),
    );
    expect(error.message).not.toContain('Detalhe interno');
    expect(
      toApiError(new HttpErrorResponse({ status: 502, error: '<html>Bad Gateway</html>' })).message,
    ).not.toContain('<html>');
  });

  it('distingue falha de rede e preserva um erro já normalizado', () => {
    const error = toApiError(new HttpErrorResponse({ status: 0 }));
    expect(error.kind).toBe('network');
    expect(toApiError(error)).toBe(error);
  });
});
