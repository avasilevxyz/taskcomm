import { describe, expect, it } from 'vitest';
import { phaseCounts } from './common';

describe('попытки по фазам', () => {
  it('считает каждую фазу отдельно, в порядке первой попытки фазы', () => {
    const attempts = ['EXPAND', 'COLLECT', 'COLLECT', 'EXPAND', 'COLLECT'].map((phase) => ({ phase }));

    expect(phaseCounts(attempts)).toEqual([
      { phase: 'EXPAND', count: 2 },
      { phase: 'COLLECT', count: 3 },
    ]);
  });

  it('без попыток — без чисел', () => {
    expect(phaseCounts([])).toEqual([]);
  });
});
