// Pure ECharts option builders for the two tabs. Kept free of Angular / echarts-runtime
// imports (only the `EChartsOption` *type*) so they unit-test as plain functions.

import type { EChartsOption } from 'echarts';
import { Row } from './models';

/** Material-flavoured palette; light/dark values chosen for contrast on each surface. */
export interface Palette {
  text: string;
  subtext: string;
  axis: string;
  split: string;
  moving: string;
  idle: string;
  tooltipBg: string;
  tooltipText: string;
  series: string[];
}

export function palette(dark: boolean): Palette {
  return dark
    ? {
        text: '#eceff1',
        subtext: '#b0bec5',
        axis: '#455a64',
        split: '#2b3a41',
        moving: '#64b5f6',
        idle: '#78909c',
        tooltipBg: '#263238',
        tooltipText: '#eceff1',
        series: SERIES_DARK,
      }
    : {
        text: '#212121',
        subtext: '#546e7a',
        axis: '#b0bec5',
        split: '#eceff1',
        moving: '#1976d2',
        idle: '#90a4ae',
        tooltipBg: '#37474f',
        tooltipText: '#ffffff',
        series: SERIES_LIGHT,
      };
}

// Material categorical hues — enough distinct colours for the fleet (up to ~10 elevators).
const SERIES_LIGHT = [
  '#1976d2', '#e53935', '#43a047', '#fb8c00', '#8e24aa',
  '#00acc1', '#c0ca33', '#6d4c41', '#3949ab', '#00897b',
];
const SERIES_DARK = [
  '#64b5f6', '#ef5350', '#81c784', '#ffb74d', '#ba68c8',
  '#4dd0e1', '#dce775', '#a1887f', '#7986cb', '#4db6ac',
];

const isMoving = (motion: string): boolean => (motion ?? '').toUpperCase() === 'MOVING';
const dirOf = (direction: string): string => (direction ?? '').toUpperCase();

function chevron(direction: string): string {
  switch (dirOf(direction)) {
    case 'UP': return '▲';
    case 'DOWN': return '▼';
    default: return '•';
  }
}

/** CHART tab — each elevator is a cab (rounded rect) parked at its floor; the cab glides
 *  when its floor changes (ECharts animates the position update). Colour = moving/idle. */
export function positionOption(rows: Row[], maxFloor: number, dark: boolean): EChartsOption {
  const p = palette(dark);
  const names = rows.map((r) => r.state.elevatorName);
  const data = rows.map((r, i) => {
    const moving = isMoving(r.state.motion);
    // Solid + glow when moving; hollow outline when idle — so state reads at a glance.
    const itemStyle = moving
      ? { color: p.moving, borderRadius: 5, borderWidth: 0, shadowBlur: 12, shadowColor: p.moving }
      : { color: 'transparent', borderRadius: 5, borderColor: p.idle, borderWidth: 2, shadowBlur: 0 };
    return {
      value: [i, r.state.floor],
      itemStyle,
      label: {
        formatter: moving ? `${chevron(r.state.direction)} ${r.state.floor}` : `${r.state.floor}`,
        color: moving ? '#ffffff' : p.subtext,
      },
    };
  });

  return {
    animationDurationUpdate: 100,
    animationEasingUpdate: 'linear',
    grid: { left: 46, right: 24, top: 24, bottom: 40 },
    tooltip: {
      trigger: 'item',
      backgroundColor: p.tooltipBg,
      borderWidth: 0,
      textStyle: { color: p.tooltipText },
      formatter: (params: unknown) => {
        const r = rows[(params as { dataIndex: number }).dataIndex];
        if (!r) return '';
        return `<b>${r.state.elevatorName}</b><br/>floor ${r.state.floor}` +
          `<br/>${r.state.direction} · ${r.state.motion}`;
      },
    },
    xAxis: {
      type: 'category',
      data: names,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: p.axis } },
      axisLabel: { color: p.text, fontWeight: 'bold' },
    },
    yAxis: {
      type: 'value',
      name: 'floor',
      min: 0,
      max: maxFloor,
      interval: 1,
      nameTextStyle: { color: p.subtext },
      axisLabel: { color: p.subtext },
      splitLine: { lineStyle: { color: p.split } },
    },
    series: [
      {
        id: 'cabs',
        type: 'scatter',
        symbol: 'roundRect',
        symbolSize: [46, 24],
        data,
        label: {
          show: true,
          color: '#ffffff',
          fontSize: 11,
          fontWeight: 'bold',
        },
        emphasis: { scale: 1.15 },
        z: 3,
      },
    ],
  };
}

/** TREND tab — floor over time, one smooth line per elevator. Series are right-aligned
 *  (front-padded with nulls) so every "now" sample sits at the right edge on a shared x. */
export function trendOption(
  rows: Row[],
  maxFloor: number,
  historyLen: number,
  dark: boolean,
): EChartsOption {
  const p = palette(dark);
  const categories = Array.from({ length: historyLen }, (_, i) => `${i - historyLen + 1}`);
  const series = rows.map((r) => ({
    id: r.state.elevatorName,
    name: r.state.elevatorName,
    type: 'line' as const,
    smooth: true,
    showSymbol: false,
    connectNulls: false,
    lineStyle: { width: 2.5 },
    emphasis: { focus: 'series' as const },
    endLabel: { show: true, formatter: '{a}', fontSize: 10 },
    data: padLeft(r.history, historyLen),
  }));

  return {
    animationDurationUpdate: 150,
    animationEasingUpdate: 'linear',
    color: p.series,
    grid: { left: 46, right: 72, top: 30, bottom: 24 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: p.tooltipBg,
      borderWidth: 0,
      textStyle: { color: p.tooltipText },
    },
    legend: {
      type: 'scroll',
      data: rows.map((r) => r.state.elevatorName),
      top: 0,
      textStyle: { color: p.subtext },
    },
    xAxis: {
      type: 'category',
      data: categories,
      boundaryGap: false,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: p.axis } },
      axisLabel: { show: false },
    },
    yAxis: {
      type: 'value',
      name: 'floor',
      min: 0,
      max: maxFloor,
      interval: 1,
      nameTextStyle: { color: p.subtext },
      axisLabel: { color: p.subtext },
      splitLine: { lineStyle: { color: p.split } },
    },
    series,
  };
}

/** Right-align a history to `len` points, padding the front with nulls (gaps ECharts skips). */
export function padLeft(history: number[], len: number): (number | null)[] {
  const tail = history.slice(-len);
  const pad = Array.from({ length: Math.max(0, len - tail.length) }, () => null);
  return [...pad, ...tail];
}
