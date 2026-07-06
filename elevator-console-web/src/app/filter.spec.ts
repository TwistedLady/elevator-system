import { nameFilter } from './filter';

describe('nameFilter', () => {
  it('matches everything for an empty or whitespace query', () => {
    expect(nameFilter('')('e1')).toBe(true);
    expect(nameFilter('   ')('anything')).toBe(true);
  });

  it('matches by case-insensitive regex', () => {
    const f = nameFilter('e[1-3]');
    expect(f('e2')).toBe(true);
    expect(f('E3')).toBe(true);
    expect(f('e9')).toBe(false);
  });

  it('falls back to substring match when the regex is invalid', () => {
    const f = nameFilter('e(');
    expect(f('some-e(-name')).toBe(true);
    expect(f('other')).toBe(false);
  });
});
