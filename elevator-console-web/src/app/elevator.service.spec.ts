import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ElevatorService } from './elevator.service';

class FakeEventSource {
  static last: FakeEventSource | undefined;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  closed = false;
  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }
  emit(state: unknown): void {
    this.onmessage?.({ data: JSON.stringify(state) });
  }
  raw(data: string): void {
    this.onmessage?.({ data });
  }
  close(): void {
    this.closed = true;
  }
}

const frame = (elevatorName: string, floor: number) => ({
  tag: 't', elevatorName, direction: 'Up', motion: 'Moving', floor,
});

describe('ElevatorService', () => {
  let svc: ElevatorService;
  let realES: typeof EventSource;

  beforeEach(() => {
    realES = globalThis.EventSource;
    (globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
    FakeEventSource.last = undefined;
    TestBed.configureTestingModule({ providers: [provideHttpClient()] });
    svc = TestBed.inject(ElevatorService);
  });

  afterEach(() => {
    (globalThis as unknown as { EventSource: unknown }).EventSource = realES;
  });

  it('opens the SSE stream and flips connected on open/error', () => {
    svc.connect();
    const es = FakeEventSource.last!;
    expect(es.url).toBe('/api/elevator/stream');
    expect(svc.connected()).toBe(false);
    es.onopen!();
    expect(svc.connected()).toBe(true);
    es.onerror!();
    expect(svc.connected()).toBe(false);
  });

  it('connects only once', () => {
    svc.connect();
    const first = FakeEventSource.last;
    svc.connect();
    expect(FakeEventSource.last).toBe(first);
  });

  it('folds frames into a name-sorted snapshot', () => {
    svc.connect();
    const es = FakeEventSource.last!;
    es.emit(frame('e2', 3));
    es.emit(frame('e1', 7));
    expect(svc.elevators().map((e) => e.elevatorName)).toEqual(['e1', 'e2']);
    expect(svc.elevators().find((e) => e.elevatorName === 'e1')!.floor).toBe(7);
  });

  it('accumulates floor history and caps it at HISTORY_LEN', () => {
    svc.connect();
    const es = FakeEventSource.last!;
    for (let f = 0; f < ElevatorService.HISTORY_LEN + 10; f++) {
      es.emit(frame('e1', f % 16));
    }
    const hist = svc.histories().get('e1')!;
    expect(hist.length).toBe(ElevatorService.HISTORY_LEN);
    expect(hist[hist.length - 1]).toBe((ElevatorService.HISTORY_LEN + 9) % 16);
  });

  it('ignores malformed and nameless frames', () => {
    svc.connect();
    const es = FakeEventSource.last!;
    es.raw('not json');
    es.emit({ floor: 3 });
    expect(svc.elevators()).toHaveLength(0);
  });

  it('closes the stream on disconnect', () => {
    svc.connect();
    const es = FakeEventSource.last!;
    svc.disconnect();
    expect(es.closed).toBe(true);
    expect(svc.connected()).toBe(false);
  });
});
