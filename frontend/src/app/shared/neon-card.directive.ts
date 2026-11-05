import { Directive, ElementRef, NgZone, OnDestroy, OnInit, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';

/** Borda neon que acompanha o mouse + inclinação 3D. Uso: <section class="card" appNeonCard> */
@Directive({ selector: '[appNeonCard]', standalone: true })
export class NeonCardDirective implements OnInit, OnDestroy {
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly zone = inject(NgZone);
  private readonly win = inject(DOCUMENT).defaultView!;

  private readonly onMove = (e: PointerEvent) => {
    const r = this.el.getBoundingClientRect();
    this.el.style.setProperty('--cx', `${e.clientX - r.left}px`);
    this.el.style.setProperty('--cy', `${e.clientY - r.top}px`);
    if (this.win.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return;
    }
    const px = (e.clientX - (r.left + r.width / 2)) / this.win.innerWidth;
    const py = (e.clientY - (r.top + r.height / 2)) / this.win.innerHeight;
    this.el.style.setProperty('--ry', `${px * 10}deg`);
    this.el.style.setProperty('--rx', `${-py * 10}deg`);
  };

  ngOnInit(): void {
    this.zone.runOutsideAngular(() => this.win.addEventListener('pointermove', this.onMove));
  }
  ngOnDestroy(): void {
    this.win.removeEventListener('pointermove', this.onMove);
  }
}
