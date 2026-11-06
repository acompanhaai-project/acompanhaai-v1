import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export interface Requisito {
  id: string;
  texto: string;
  ok: (s: string) => boolean;
}

export const REQUISITOS: Requisito[] = [
  { id: 'tam', texto: 'Pelo menos 8 caracteres', ok: (s) => s.length >= 8 },
  { id: 'min', texto: 'Uma letra minúscula', ok: (s) => /[a-zà-ÿ]/.test(s) },
  { id: 'mai', texto: 'Uma letra maiúscula', ok: (s) => /[A-ZÀ-Ý]/.test(s) },
  { id: 'num', texto: 'Um número', ok: (s) => /\d/.test(s) },
  { id: 'esp', texto: 'Um símbolo (ex.: ! @ # $)', ok: (s) => /[^A-Za-z0-9À-ÿ\s]/.test(s) },
];

/** Validador de campo: todos os requisitos precisam ser atendidos. */
export const senhaForte: ValidatorFn = (c: AbstractControl): ValidationErrors | null =>
  REQUISITOS.every((r) => r.ok(c.value ?? '')) ? null : { senhaFraca: true };

/** Validador de grupo: campos "senha" e "confirma" devem ser iguais. */
export const senhasIguais: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
  const s = g.get('senha')?.value;
  const c = g.get('confirma')?.value;
  return c && s !== c ? { senhasDiferentes: true } : null;
};
