import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  ViewChild,
  inject,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';

/**
 * Brilho azul neon (com blur) que segue o mouse + ponto luminoso.
 * Também publica --mx / --my no <body> para a grade do fundo acender ao redor do cursor.
 * Roda fora da zona do Angular para não disparar change detection a cada movimento.
 */
@Component({
  selector: 'app-neon-cursor',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #glow class="glow"></div>
    <div #dot class="dot"></div>`,
  styles: [
    `
      :host {
        display: contents;
      }
      .glow,
      .dot {
        position: fixed;
        left: 0;
        top: 0;
        pointer-events: none;
        z-index: 50;
        border-radius: 50%;
        opacity: 0;
        transition: opacity 0.3s;
      }
      .glow {
        width: 340px;
        height: 340px;
        margin: -170px 0 0 -170px;
        filter: blur(22px);
        background: radial-gradient(
          circle,
          rgba(47, 123, 255, 0.55),
          rgba(0, 194, 255, 0.18) 45%,
          transparent 70%
        );
        mix-blend-mode: screen;
      }
      .dot {
        width: 12px;
        height: 12px;
        margin: -6px 0 0 -6px;
        background: #7fd8ff;
        box-shadow: 0 0 14px 4px var(--neon-2);
      }
      :host(.live) .glow,
      :host(.live) .dot {
        opacity: 1;
      }
      @media (hover: none), (pointer: coarse) {
        .glow,
        .dot {
          display: none;
        }
      }
    `,
  ],
  host: { '[class.live]': 'false' },
})
export class NeonCursorComponent implements AfterViewInit, OnDestroy {
  @ViewChild('glow', { static: true }) glow!: ElementRef<HTMLElement>;
  @ViewChild('dot', { static: true }) dot!: ElementRef<HTMLElement>;

  private readonly zone = inject(NgZone);
  private readonly doc = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private tx = 0;
  private ty = 0;
  private gx = 0;
  private gy = 0;
  private raf = 0;
  private reduce = false;

  private readonly onMove = (e: PointerEvent) => {
    this.tx = e.clientX;
    this.ty = e.clientY;
    this.host.nativeElement.classList.add('live');
    const body = this.doc.body;
    body.style.setProperty('--mx', `${this.tx}px`);
    body.style.setProperty('--my', `${this.ty}px`);
    this.dot.nativeElement.style.transform = `translate(${this.tx}px, ${this.ty}px)`;
  };
  private readonly onLeave = () => this.host.nativeElement.classList.remove('live');

  ngAfterViewInit(): void {
    const win = this.doc.defaultView!;
    this.reduce = win.matchMedia('(prefers-reduced-motion: reduce)').matches;
    this.tx = this.gx = win.innerWidth / 2;
    this.ty = this.gy = win.innerHeight / 2;

    this.zone.runOutsideAngular(() => {
      win.addEventListener('pointermove', this.onMove);
      this.doc.addEventListener('pointerleave', this.onLeave);
      const loop = () => {
        const k = this.reduce ? 1 : 0.12; // suavização (atraso do brilho)
        this.gx += (this.tx - this.gx) * k;
        this.gy += (this.ty - this.gy) * k;
        this.glow.nativeElement.style.transform = `translate(${this.gx}px, ${this.gy}px)`;
        this.raf = requestAnimationFrame(loop);
      };
      loop();
    });
  }

  ngOnDestroy(): void {
    const win = this.doc.defaultView!;
    win.removeEventListener('pointermove', this.onMove);
    this.doc.removeEventListener('pointerleave', this.onLeave);
    cancelAnimationFrame(this.raf);
  }
}
