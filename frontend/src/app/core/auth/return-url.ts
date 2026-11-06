export const DEFAULT_RETURN_URL = '/conta';

export function safeReturnUrl(value: string | null | undefined): string {
  if (
    !value ||
    !/^\/conta(?:[?#].*)?$/.test(value) ||
    /\s|\\/.test(value) ||
    Array.from(value).some(
      (character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
    )
  ) {
    return DEFAULT_RETURN_URL;
  }
  return value;
}
