// JS interop for Elm. Two seams: the <echarts-panel> custom element (Elm owns the ECharts
// option, this element owns pixels/resize/tooltip formatter) and ports for genuine event
// sources only — the SSE stream and OS colour-scheme changes.
import { Elm } from './Main.elm';
import * as echarts from 'echarts/core';
import { LineChart, ScatterChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { APP_VERSION } from './generated/version.js';
import './styles.css';

echarts.use([
  LineChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);

function withFormatters(option) {
  if (option && option.__kind === 'position') {
    option.tooltip = Object.assign({}, option.tooltip, {
      formatter: (p) => (p && p.data && p.data.tip) || '',
    });
  }
  return option;
}

customElements.define(
  'echarts-panel',
  class extends HTMLElement {
    connectedCallback() {
      this._chart = echarts.init(this, null, { renderer: 'canvas' });
      this._observer = new ResizeObserver(() => this._chart && this._chart.resize());
      this._observer.observe(this);
      this._render();
    }

    disconnectedCallback() {
      if (this._observer) this._observer.disconnect();
      if (this._chart) this._chart.dispose();
    }

    set option(value) {
      this._option = value;
      this._render();
    }

    _render() {
      if (!this._chart || !this._option) return;
      const option = withFormatters(this._option);
      this._chart.setOption(option, {
        lazyUpdate: true,
        replaceMerge: option.__replace ? ['series'] : undefined,
      });
    }
  },
);

const darkMedia = window.matchMedia('(prefers-color-scheme: dark)');

const app = Elm.Main.init({
  node: document.getElementById('app'),
  flags: { version: APP_VERSION, dark: darkMedia.matches },
});

app.ports.connectStream.subscribe(() => {
  const source = new EventSource('/api/elevator/stream');
  source.onopen = () => app.ports.streamOpened.send(null);
  source.onerror = () => app.ports.streamErrored.send(null);
  source.onmessage = (event) => {
    try {
      app.ports.streamFrame.send(JSON.parse(event.data));
    } catch (_err) {
    }
  };
});

darkMedia.addEventListener('change', (event) => app.ports.themeChanged.send(event.matches));

const LOG_PREFIX = '[web-console]';
const LOG_ORDER = { debug: 0, info: 1, warn: 2, error: 3 };
const LOG_THRESHOLD = LOG_ORDER[import.meta.env.DEV ? 'debug' : 'info'];
app.ports.logMessage.subscribe(({ level, message }) => {
  if ((LOG_ORDER[level] ?? 1) < LOG_THRESHOLD) return;
  (console[level] || console.log)(LOG_PREFIX, message);
});
