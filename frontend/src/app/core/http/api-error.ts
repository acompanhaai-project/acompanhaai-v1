import { HttpErrorResponse } from '@angular/common/http';

export type ApiErrorKind =
  | 'network'
  | 'authentication'
  | 'forbidden'
  | 'conflict'
  | 'validation'
  | 'rate-limit'
  | 'unexpected';

export interface ApiFieldError {
  readonly field: string;
  readonly message: string;
}

export class ApiError extends Error {
  override readonly name = 'ApiError';

  constructor(
    readonly status: number,
    readonly kind: ApiErrorKind,
    message: string,
    readonly fieldErrors: readonly ApiFieldError[] = [],
    readonly code: string | null = null,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message);
  }
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

export function toApiError(cause: unknown): ApiError {
  if (cause instanceof ApiError) {
    return cause;
  }
  if (!(cause instanceof HttpErrorResponse)) {
    return new ApiError(0, 'unexpected', 'Não foi possível concluir a operação.');
  }

  const body = record(cause.error);
  const status = cause.status;
  const details: Record<number, readonly [ApiErrorKind, string]> = {
    0: [
      'network',
      'Não foi possível receber uma resposta do servidor. Verifique a conexão antes de tentar novamente.',
    ],
    400: ['validation', 'Confira os dados enviados.'],
    401: ['authentication', 'Entre na sua conta para continuar.'],
    403: [
      'forbidden',
      'Não foi possível autorizar a operação. Confira sua sessão antes de tentar novamente.',
    ],
    404: ['unexpected', 'O registro solicitado não está disponível.'],
    409: ['conflict', 'Há um conflito com os dados atuais. Recarregue e revise sua alteração.'],
    413: ['validation', 'O arquivo enviado ultrapassa o limite permitido.'],
    422: ['validation', 'Confira os campos informados.'],
    429: ['rate-limit', 'Limite de solicitações atingido. Aguarde antes de tentar novamente.'],
  };
  const [kind, fallback] = details[status] ?? [
    'unexpected',
    'O servidor não conseguiu concluir a operação. Tente novamente mais tarde.',
  ];
  const detail = typeof body['detail'] === 'string' ? body['detail'] : body['error'];
  const message =
    status >= 400 && status < 500 && status !== 403 && typeof detail === 'string' && detail.trim()
      ? detail
      : fallback;
  const fields = Array.isArray(body['errors'])
    ? body['errors']
        .map(record)
        .filter((item) => typeof item['field'] === 'string' && typeof item['message'] === 'string')
        .map((item) => ({ field: item['field'] as string, message: item['message'] as string }))
    : [];
  const retryHeader = cause.headers.get('Retry-After');
  const retry = retryHeader !== null && /^\d+$/.test(retryHeader) ? Number(retryHeader) : null;

  return new ApiError(
    status,
    kind,
    message,
    fields,
    typeof body['code'] === 'string' ? body['code'] : null,
    status === 429 && retry !== null && Number.isSafeInteger(retry) ? retry : null,
  );
}
