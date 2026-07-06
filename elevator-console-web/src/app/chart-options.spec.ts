import { padLeft, palette, positionOption, trendOption } from './chart-options';
import { Row } from './models';

const row = (name: string, floor: number, motion = 'Stopped', direction = 'Up', history: number[] = []): Row => ({
  state: { tag: 't', elevatorName: name, motion, direction, floor },
  history,
});

describe('padLeft', () => {
  it('front-pads a short history with nulls to the target length', () => {
    expect(padLeft([3, 4], 4)).toEqual([null, null, 3, 4]);
  });

  it('keeps only the most recent `len` samples', () => {
    expect(padLeft([1, 2, 3, 4, 5], 3)).toEqual([3, 4, 5]);
  });

  it('is all nulls for an empty history', () => {
    expect(padLeft([], 3)).toEqual([null, null, null]);
  });
});

describe('positionOption', () => {
  it('puts each elevator on the category axis at its floor', () => {
    const opt: any = positionOption([row('e1', 2), row('e2', 7)], 15, false);
    expect(opt.xAxis.data).toEqual(['e1', 'e2']);
    expect(opt.yAxis.max).toBe(15);
    const data = opt.series[0].data;
    expect(data.map((d: any) => d.value)).toEqual([[0, 2], [1, 7]]);
  });

  it('fills moving cabs with the accent and leaves idle cabs hollow (outlined)', () => {
    const p = palette(false);
    const opt: any = positionOption([row('e1', 2, 'Moving'), row('e2', 7, 'Stopped')], 15, false);
    expect(opt.series[0].data[0].itemStyle.color).toBe(p.moving);
    expect(opt.series[0].data[1].itemStyle.color).toBe('transparent');
    expect(opt.series[0].data[1].itemStyle.borderColor).toBe(p.idle);
  });

  it('labels the cab with a direction chevron and its floor', () => {
    const opt: any = positionOption([row('e1', 5, 'Moving', 'Down')], 15, false);
    expect(opt.series[0].data[0].label.formatter).toBe('▼ 5');
  });
});

describe('trendOption', () => {
  it('builds one right-aligned line series per elevator', () => {
    const opt: any = trendOption([row('e1', 4, 'Moving', 'Up', [2, 3, 4])], 15, 4, false);
    expect(opt.series).toHaveLength(1);
    expect(opt.series[0].name).toBe('e1');
    expect(opt.series[0].data).toEqual([null, 2, 3, 4]);
    expect(opt.legend.data).toEqual(['e1']);
  });
});
