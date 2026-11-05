import { Injectable } from '@angular/core';
import { finalize, Observable, Subscription } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class RequestQueue {
  private tail: Promise<void> = Promise.resolve();

  exclusive<T>(operation: () => Observable<T>): Observable<T> {
    return new Observable<T>((observer) => {
      const previous = this.tail;
      let release!: () => void;
      let cancelled = false;
      let subscription: Subscription | undefined;

      this.tail = new Promise<void>((resolve) => {
        release = resolve;
      });

      void previous.then(() => {
        if (cancelled) {
          release();
          return;
        }
        try {
          subscription = operation().pipe(finalize(release)).subscribe(observer);
        } catch (error) {
          release();
          observer.error(error);
        }
      });

      return () => {
        cancelled = true;
        subscription?.unsubscribe();
      };
    });
  }
}
