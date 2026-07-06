import { Component, computed, input } from '@angular/core';
import { ElevatorState } from './models';

// CHART tab — one elevator as a small-multiple shaft: faint floor gridlines and a cab
// that animates to its current floor each tick. Identity is spatial (the labelled column),
// so colour only carries state: accent = MOVING, muted = IDLE. History lives in the Trend tab.
export const PAD = 14;      // top/bottom padding inside the shaft plot
export const PLOT_H = 300;  // shaft plot height
const W = 64;               // shaft width
const CAB_H = 16;

/** Floor → y pixel on the shared shaft scale; used by the shaft, the axis, and the trend cells. */
export function floorToY(floor: number, maxFloor: number): number {
  return PAD + ((maxFloor - floor) / maxFloor) * (PLOT_H - 2 * PAD);
}

@Component({
  selector: 'app-shaft',
  imports: [],
  templateUrl: './shaft.html',
  styleUrl: './shaft.css',
})
export class Shaft {
  readonly state = input.required<ElevatorState>();
  readonly maxFloor = input<number>(15);

  protected readonly w = W;
  protected readonly plotH = PLOT_H;
  protected readonly cabH = CAB_H;

  // The api serialises the core enums as Motion=Moving|Stopped, Direction=Up|Down.
  // Compare case-insensitively so a capitalisation change on the api side can't break it.
  protected readonly moving = computed(() => this.state().motion?.toUpperCase() === 'MOVING');

  /** Normalised direction (UP | DOWN) for the chevron glyph and the data-dir colour hook. */
  protected readonly dir = computed(() => this.state().direction?.toUpperCase());

  protected readonly cabY = computed(() => floorToY(this.state().floor, this.maxFloor()) - CAB_H / 2);

  protected readonly gridlines = computed(() =>
    Array.from({ length: this.maxFloor() + 1 }, (_, f) => ({ floor: f, y: floorToY(f, this.maxFloor()) })));

  protected readonly chevron = computed(() => {
    switch (this.dir()) {
      case 'UP': return '▲';
      case 'DOWN': return '▼';
      default: return '•';
    }
  });
}
