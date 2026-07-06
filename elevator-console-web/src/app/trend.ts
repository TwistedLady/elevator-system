import { Component, computed, input } from '@angular/core';
import { ElevatorState } from './models';
import { PLOT_H, floorToY } from './shaft';

// TREND tab — one elevator's floor over time as a small-multiple line chart.
// Single series per cell (so no categorical-colour cap issue with 10 elevators);
// all cells share the floor scale (floorToY) and the Chart tab's y-axis.
const W = 150;
const PADX = 8;

@Component({
  selector: 'app-trend',
  imports: [],
  templateUrl: './trend.html',
  styleUrl: './trend.css',
})
export class Trend {
  readonly state = input.required<ElevatorState>();
  readonly history = input<number[]>([]);
  readonly maxFloor = input.required<number>();

  protected readonly w = W;
  protected readonly plotH = PLOT_H;

  protected readonly gridlines = computed(() =>
    Array.from({ length: this.maxFloor() + 1 }, (_, f) => ({ floor: f, y: floorToY(f, this.maxFloor()) })));

  private x(i: number, n: number): number {
    return n < 2 ? W - PADX : PADX + (i / (n - 1)) * (W - 2 * PADX);
  }

  protected readonly linePath = computed(() => {
    const h = this.history();
    if (h.length < 2) {
      return '';
    }
    return h
      .map((v, i) => `${i === 0 ? 'M' : 'L'}${this.x(i, h.length).toFixed(1)},${floorToY(v, this.maxFloor()).toFixed(1)}`)
      .join(' ');
  });

  /** Latest sample marker (the "now" dot at the right edge). */
  protected readonly dot = computed(() => {
    const h = this.history();
    if (h.length === 0) {
      return null;
    }
    return { x: this.x(h.length - 1, h.length), y: floorToY(h[h.length - 1], this.maxFloor()) };
  });
}
