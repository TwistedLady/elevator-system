import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, Observable } from 'rxjs';
import { ElevatorState, MileageStat, OrdersServedStat } from './models';
import { log } from './logger';

// All calls are relative so the same build works behind the dev proxy (proxy.conf.json → :8080)
// and when the compiled bundle is served from the same origin as the api.
// App is zoneless (Angular 21 bootstrapApplication default), so signal writes from the SSE
// callback drive change detection directly — no NgZone needed.
@Injectable({ providedIn: 'root' })
export class ElevatorService {
  private readonly http = inject(HttpClient);

  /** How many recent floor samples the Trend tab keeps per elevator. */
  static readonly HISTORY_LEN = 48;

  private readonly byName = signal<Map<string, ElevatorState>>(new Map());
  private readonly hist = signal<Map<string, number[]>>(new Map());
  private source?: EventSource;

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
    log.info('connecting SSE → /api/elevator/stream');
    const es = new EventSource('/api/elevator/stream');
    this.source = es;
    es.onopen = () => {
      log.info('SSE open');
      this.connected.set(true);
    };
    es.onerror = () => {
      // EventSource retries on its own; just reflect the drop in the badge.
      if (this.connected()) {
        log.warn('SSE error — connection dropped, awaiting auto-reconnect');
      }
      this.connected.set(false);
    };
    es.onmessage = (ev) => this.ingest(ev.data);
  }

  /** Fold one SSE frame into the live snapshot + rolling history. Malformed frames are dropped. */
  private ingest(raw: string): void {
    let state: ElevatorState;
    try {
      state = JSON.parse(raw) as ElevatorState;
    } catch {
      log.warn('dropped malformed SSE frame', raw);
      return;
    }
    if (!state?.elevatorName) {
      log.warn('dropped SSE frame without elevatorName', state);
      return;
    }

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
  }

  disconnect(): void {
    log.info('disconnecting SSE');
    this.source?.close();
    this.source = undefined;
    this.connected.set(false);
  }

  async health(): Promise<{ status: string }> {
    return firstValueFrom(this.http.get<{ status: string }>('/actuator/health'));
  }

  /** Spark BI mileage per elevator (floors travelled). Polled by the Stats tab. */
  mileage(): Observable<MileageStat[]> {
    return this.http.get<MileageStat[]>('/api/mileage');
  }

  /** Spark BI orders-served per elevator (reached ordered floors). Polled by the Stats tab. */
  served(): Observable<OrdersServedStat[]> {
    return this.http.get<OrdersServedStat[]>('/api/served');
  }

  version(): Promise<{ version: string }> {
    return firstValueFrom(this.http.get<{ version: string }>('/api/version'));
  }
}
