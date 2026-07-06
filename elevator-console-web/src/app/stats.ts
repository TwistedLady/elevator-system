import { Component, OnInit, OnDestroy, signal, computed, input, inject } from '@angular/core';
import { Subscription, timer, switchMap, forkJoin, of, catchError } from 'rxjs';
import { ElevatorService } from './elevator.service';
import { MileageStat, OrdersServedStat } from './models';

// STATS tab — Spark BI outcomes per elevator, polled from the api.
//   mileage       GET /api/mileage  (floors travelled)
//   orders served GET /api/served   (reached ordered floors)
// Merged into one row per elevator and drawn as a small horizontal bar chart.
const POLL_MS = 4000;

interface StatRow {
  name: string;
  mileage: number;
  served: number;
}

@Component({
  selector: 'app-stats',
  imports: [],
  templateUrl: './stats.html',
  styleUrl: './stats.css',
})
export class Stats implements OnInit, OnDestroy {
  private readonly api = inject(ElevatorService);

  /** Shared regex name filter (same behaviour as the Chart/Trend tabs). */
  readonly filter = input('');

  private sub?: Subscription;
  private readonly mileage = signal<MileageStat[]>([]);
  private readonly served = signal<OrdersServedStat[]>([]);
  /** True once at least one poll has returned (so we can tell "empty" from "loading"). */
  private readonly loaded = signal(false);
  protected readonly failed = signal(false);

  /** One row per elevator, mileage + served merged, sorted by name (e1, e2, … natural order). */
  private readonly rows = computed<StatRow[]>(() => {
    const merged = new Map<string, StatRow>();
    const get = (name: string) =>
      merged.get(name) ?? merged.set(name, { name, mileage: 0, served: 0 }).get(name)!;
    for (const m of this.mileage()) {
      get(m.elevatorName).mileage = m.floorsTravelled;
    }
    for (const s of this.served()) {
      get(s.elevatorName).served = s.ordersServed;
    }
    return [...merged.values()].sort((a, b) =>
      a.name.localeCompare(b.name, undefined, { numeric: true }));
  });

  /** Rows whose name matches the filter (regex, falling back to substring; case-insensitive). */
  protected readonly filtered = computed(() => {
    const q = this.filter().trim();
    if (!q) {
      return this.rows();
    }
    let test: (name: string) => boolean;
    try {
      const re = new RegExp(q, 'i');
      test = (name) => re.test(name);
    } catch {
      const lower = q.toLowerCase();
      test = (name) => name.toLowerCase().includes(lower);
    }
    return this.rows().filter((r) => test(r.name));
  });

  protected readonly loading = computed(() => !this.loaded());
  protected readonly noData = computed(() => this.loaded() && this.rows().length === 0);

  protected readonly maxMileage = computed(() =>
    Math.max(1, ...this.filtered().map((r) => r.mileage)));
  protected readonly maxServed = computed(() =>
    Math.max(1, ...this.filtered().map((r) => r.served)));

  /** Bar width as a percentage of the metric's max across the visible rows. */
  protected pct(value: number, max: number): number {
    return max > 0 ? (value / max) * 100 : 0;
  }

  ngOnInit(): void {
    // Poll both endpoints together; a failed poll keeps the last good data and flags it,
    // and the timer keeps running (catchError swallows the error inside the switchMap).
    this.sub = timer(0, POLL_MS)
      .pipe(
        switchMap(() =>
          forkJoin({ mileage: this.api.mileage(), served: this.api.served() }).pipe(
            catchError(() => of(null)),
          )),
      )
      .subscribe((res) => {
        if (res) {
          this.mileage.set(res.mileage);
          this.served.set(res.served);
          this.failed.set(false);
        } else {
          this.failed.set(true);
        }
        this.loaded.set(true);
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
