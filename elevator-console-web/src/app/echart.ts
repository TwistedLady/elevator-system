// Thin Angular wrapper around an ECharts instance. Zoneless-friendly: it initialises the
// chart after the first render, then re-applies options from an `effect` whenever the input
// signal changes, and resizes with the host element via a ResizeObserver.

import {
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  effect,
  inject,
  input,
} from '@angular/core';
import * as echarts from 'echarts/core';
import { LineChart, ScatterChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';
import { log } from './logger';

echarts.use([
  LineChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);

@Component({
  selector: 'app-echart',
  template: '',
  styles: [':host { display: block; width: 100%; height: 100%; }'],
})
export class Echart implements OnDestroy {
  readonly options = input.required<EChartsOption>();
  /** Replace series on update (Trend), so removing a filtered-out elevator drops its line. */
  readonly replaceSeries = input(false);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private chart?: echarts.ECharts;
  private observer?: ResizeObserver;

  constructor() {
    afterNextRender(() => this.init());
    effect(() => {
      const opts = this.options();
      if (this.chart) {
        this.chart.setOption(opts, {
          lazyUpdate: true,
          replaceMerge: this.replaceSeries() ? ['series'] : undefined,
        });
      }
    });
  }

  private init(): void {
    const el = this.host.nativeElement;
    this.chart = echarts.init(el, undefined, { renderer: 'canvas' });
    this.chart.setOption(this.options());
    this.observer = new ResizeObserver(() => this.chart?.resize());
    this.observer.observe(el);
    log.debug('echart initialised', `${el.clientWidth}x${el.clientHeight}`);
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    this.chart?.dispose();
    log.debug('echart disposed');
  }
}
