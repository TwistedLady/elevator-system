import {
  Component,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ElevatorService } from './elevator.service';
import { Echart } from './echart';
import { Stats } from './stats';
import { Row } from './models';
import { nameFilter } from './filter';
import { positionOption, trendOption } from './chart-options';
import { APP_VERSION } from './version';
import { log } from './logger';

const HEALTH_POLL_MS = 15_000;

@Component({
  selector: 'app-root',
  imports: [FormsModule, Echart, Stats],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  private readonly api = inject(ElevatorService);
  private readonly darkMedia = window.matchMedia('(prefers-color-scheme: dark)');
  private healthTimer?: ReturnType<typeof setInterval>;
  private onThemeChange = () => this.dark.set(this.darkMedia.matches);

  protected readonly connected = this.api.connected;
  /** Live floor range from the api (GET /api/config), not a compile-time constant. */
  protected readonly maxFloor = this.api.maxFloor;
  protected readonly historyLen = ElevatorService.HISTORY_LEN;
  protected readonly health = signal<string>('unknown');
  protected readonly dark = signal(this.darkMedia.matches);

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
  /** Regex name filter, shared by all tabs (same behaviour as the console). */
  protected readonly filter = signal('');

  /** Whether the BI layer is on (from /api/config); when off, the Stats tab is hidden. */
  protected readonly biEnabled = this.api.biEnabled;
  /** If BI gets turned off while the Stats tab is open, fall back to Chart. */
  private readonly biTabGuard = effect(() => {
    if (!this.biEnabled() && this.tab() === 'stats') {
      this.tab.set('chart');
    }
  });

  protected readonly noData = computed(() => this.api.elevators().length === 0);

  private readonly rows = computed<Row[]>(() =>
    this.api.elevators().map((state) => ({
      state,
      history: this.api.histories().get(state.elevatorName) ?? [],
    })));

  protected readonly filtered = computed(() => {
    const match = nameFilter(this.filter());
    return this.rows().filter((r) => match(r.state.elevatorName));
  });

  protected readonly chartOption = computed(() =>
    positionOption(this.filtered(), this.maxFloor(), this.dark()));

  protected readonly trendOption = computed(() =>
    trendOption(this.filtered(), this.maxFloor(), this.historyLen, this.dark()));

  ngOnInit(): void {
    this.api.connect();
    this.api.loadConfig();
    this.refreshHealth();
    this.healthTimer = setInterval(() => this.refreshHealth(), HEALTH_POLL_MS);
    this.darkMedia.addEventListener('change', this.onThemeChange);
    this.checkVersion();
  }

  ngOnDestroy(): void {
    clearInterval(this.healthTimer);
    this.darkMedia.removeEventListener('change', this.onThemeChange);
    this.api.disconnect();
  }

  async refreshHealth(): Promise<void> {
    try {
      this.health.set((await this.api.health()).status);
    } catch {
      log.warn('health check failed');
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
