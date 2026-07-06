import { Injectable, signal, computed, NgZone, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ElevatorState } from './models';

// All calls are relative so the same build works behind the dev proxy (proxy.conf.json → :8080)
// and when the compiled bundle is served from the same origin as the api.
@Injectable({ providedIn: 'root' })
export class ElevatorService {
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);

  /** How many recent floor samples the Trend tab keeps per elevator. */
  static readonly HISTORY_LEN = 48;

  private readonly byName = signal<Map<string, ElevatorState>>(new Map());
  private readonly hist = signal<Map<string, number[]>>(new Map());
  private source?: EventSource;

  /** Live max floor from the api (GET /api/config); 0 until the first fetch. Never hardcoded. */
  private readonly maxFloorSig = signal<number>(0);
  readonly maxFloor = this.maxFloorSig.asReadonly();
  private configTimer?: ReturnType<typeof setInterval>;

  /** Live elevator snapshots, sorted by name (e1, e2, … natural order). */
  readonly elevators = computed(() =>
    [...this.byName().values()].sort((a, b) =>
      a.elevatorName.localeCompare(b.elevatorName, undefined, { numeric: true })));

  /** Recent floor history per elevator (oldest → newest), for the Trend tab. */
  readonly histories = this.hist.asReadonly();

  readonly connected = signal(false);

  /** Subscribe to the SSE stream; the browser auto-reconnects across backend restarts. */
  connect(): void {
    if (this.source) {
      return;
    }
    const es = new EventSource('/api/elevator/stream');
    this.source = es;
    es.onopen = () => this.zone.run(() => this.connected.set(true));
    es.onerror = () => this.zone.run(() => this.connected.set(false));
    es.onmessage = (ev) => {
      try {
        const state = JSON.parse(ev.data) as ElevatorState;
        this.zone.run(() => {
          const next = new Map(this.byName());
          next.set(state.elevatorName, state);
          this.byName.set(next);

          const hist = new Map(this.hist());
          const series = [...(hist.get(state.elevatorName) ?? []), state.floor];
          if (series.length > ElevatorService.HISTORY_LEN) {
            series.splice(0, series.length - ElevatorService.HISTORY_LEN);
          }
          hist.set(state.elevatorName, series);
          this.hist.set(hist);
        });
      } catch {
        // ignore malformed frames
      }
    };
  }

  disconnect(): void {
    this.source?.close();
    this.source = undefined;
    this.connected.set(false);
    if (this.configTimer) {
      clearInterval(this.configTimer);
      this.configTimer = undefined;
    }
  }

  /** Fetch the live limits from the api and keep them fresh, so ConfigMap hot-reloads show up here. */
  loadConfig(): void {
    void this.fetchConfig();
    this.configTimer ??= setInterval(() => void this.fetchConfig(), 10_000);
  }

  private async fetchConfig(): Promise<void> {
    try {
      const cfg = await firstValueFrom(
        this.http.get<{ maxFloor: number; elevators: string[] }>('/api/config'));
      this.maxFloorSig.set(cfg.maxFloor);
    } catch {
      // keep last known limits
    }
  }

  health(): Promise<{ status: string }> {
    return firstValueFrom(this.http.get<{ status: string }>('/actuator/health'));
  }
}
