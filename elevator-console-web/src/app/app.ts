import { Component, OnInit, OnDestroy, signal, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ElevatorService } from './elevator.service';
import { Shaft, PLOT_H, floorToY } from './shaft';
import { Trend } from './trend';
import { Stats } from './stats';
import { APP_VERSION } from './version';

// Floor range mirrors elevator-api application.yml (max-floor 15).
const MAX_FLOOR = 15;

@Component({
  selector: 'app-root',
  imports: [FormsModule, Shaft, Trend, Stats],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  private readonly api = inject(ElevatorService);

  protected readonly connected = this.api.connected;
  protected readonly maxFloor = MAX_FLOOR;
  protected readonly plotH = PLOT_H;
  protected readonly health = signal<string>('unknown');

  /** This build's version (baked in from the repo-root VERSION file) vs the backend's. */
  protected readonly webVersion = APP_VERSION;
  protected readonly backendVersion = signal<string>('unknown');
  /** True once we've read a backend version that matches this build. */
  protected readonly versionMatch = computed(() => this.backendVersion() === this.webVersion);
  /** Warn only once we've actually read a real backend version that differs from this build. */
  protected readonly versionMismatch = computed(() => {
    const backend = this.backendVersion();
    return backend !== 'unknown' && backend !== 'unreachable' && backend !== this.webVersion;
  });

  /** Chart + Trend mirror the Rust console; Stats adds the Spark BI outcomes. */
  protected readonly tab = signal<'chart' | 'trend' | 'stats'>('chart');
  /** Regex name filter, shared by both tabs (same behaviour as the console). */
  protected readonly filter = signal('');

  protected readonly noData = computed(() => this.api.elevators().length === 0);

  /** Per-elevator view-model: live state paired with its floor history. */
  private readonly rows = computed(() =>
    this.api.elevators().map((e) => ({
      state: e,
      history: this.api.histories().get(e.elevatorName) ?? [],
    })));

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
    return this.rows().filter((r) => test(r.state.elevatorName));
  });

  /** Shared left floor axis, aligned to the shaft/trend scale. */
  protected readonly axisTicks = computed(() =>
    Array.from({ length: MAX_FLOOR + 1 }, (_, f) => ({ floor: f, y: floorToY(f, MAX_FLOOR) })));

  ngOnInit(): void {
    this.api.connect();
    this.refreshHealth();
    this.checkVersion();
  }

  ngOnDestroy(): void {
    this.api.disconnect();
  }

  async refreshHealth(): Promise<void> {
    try {
      this.health.set((await this.api.health()).status);
    } catch {
      this.health.set('DOWN');
    }
  }

  async checkVersion(): Promise<void> {
    try {
      this.backendVersion.set((await this.api.version()).version);
    } catch {
      this.backendVersion.set('unreachable');
    }
  }
}
