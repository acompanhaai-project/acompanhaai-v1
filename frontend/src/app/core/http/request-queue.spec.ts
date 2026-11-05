import { of, Subject } from 'rxjs';
import { RequestQueue } from './request-queue';

async function turn(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

describe('Coordenação das escritas', () => {
  it('cancelar um item pendente não permite ultrapassar uma operação ativa', async () => {
    const queue = new RequestQueue();
    const first = new Subject<void>();
    const started: string[] = [];
    queue
      .exclusive(() => {
        started.push('first');
        return first;
      })
      .subscribe();
    const cancelled = queue
      .exclusive(() => {
        started.push('cancelled');
        return of(undefined);
      })
      .subscribe();
    queue
      .exclusive(() => {
        started.push('last');
        return of(undefined);
      })
      .subscribe();
    cancelled.unsubscribe();
    await turn();
    expect(started).toEqual(['first']);
    first.complete();
    await turn();
    expect(started).toEqual(['first', 'last']);
  });

  it('uma exceção não bloqueia operações seguintes', async () => {
    const queue = new RequestQueue();
    const errors: unknown[] = [];
    queue
      .exclusive(() => {
        throw new Error('Simulação de falha');
      })
      .subscribe({ error: (error: unknown) => errors.push(error) });
    let finished = false;
    queue
      .exclusive(() => of(undefined))
      .subscribe(() => {
        finished = true;
      });
    await turn();
    expect(errors).toHaveLength(1);
    expect(finished).toBe(true);
  });
});
