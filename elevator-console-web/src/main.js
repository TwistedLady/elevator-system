// JS interop layer. Two clean seams to Elm:
//   1. <echarts-panel> custom element — wraps an ECharts canvas instance. Elm owns the option
//      (built in Chart.elm); this element owns the pixels, resize, and the JS-only tooltip
//      formatter. Custom elements avoid the DOM-timing races a chart-via-port would hit.
//   2. Ports — for genuine event sources only: the SSE stream and the OS colour-scheme change.
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

// The tooltip formatter is the only JS-only bit of the option; everything else is plain data from
// Elm. The position chart carries a per-point `tip` string; the trend chart uses the axis default.
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

    // Elm sets this DOM property (Html.Attributes.property "option" ...) on every model change.
    set option(value) {
      this._option = value;
      this._render();
    }

    _render() {
      if (!this._chart || !this._option) return;
      const option = withFormatters(this._option);
      this._chart.setOption(option, {
        lazyUpdate: true,
        // Trend replaces series so a filtered-out elevator's line disappears; position merges.
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

// SSE: Elm asks us to connect once; the browser auto-reconnects across backend restarts, and we
// mirror open/error/frame back through incoming ports.
app.ports.connectStream.subscribe(() => {
  const source = new EventSource('/api/elevator/stream');
  source.onopen = () => app.ports.streamOpened.send(null);
  source.onerror = () => app.ports.streamErrored.send(null);
  source.onmessage = (event) => {
    try {
      app.ports.streamFrame.send(JSON.parse(event.data));
    } catch (_err) {
      // malformed frame — Elm's decoder would drop it too; ignore here.
    }
  };
});

darkMedia.addEventListener('change', (event) => app.ports.themeChanged.send(event.matches));

// Logging sink for the Log port: shared [web-console] prefix + a level threshold (debug in dev,
// info and up in a production build), so devtools output is easy to filter.
const LOG_PREFIX = '[web-console]';
const LOG_ORDER = { debug: 0, info: 1, warn: 2, error: 3 };
const LOG_THRESHOLD = LOG_ORDER[import.meta.env.DEV ? 'debug' : 'info'];
app.ports.logMessage.subscribe(({ level, message }) => {
  if ((LOG_ORDER[level] ?? 1) < LOG_THRESHOLD) return;
  (console[level] || console.log)(LOG_PREFIX, message);
});
