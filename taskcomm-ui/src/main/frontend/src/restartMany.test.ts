import { describe, expect, it } from 'vitest';
import { acceptsChildRestart, taskRestartable } from './restartMany';

describe('правила перезапуска', () => {
  it('подзадача — только упавшая, группа и задача — завершённые', () => {
    expect(taskRestartable('UNIT', 'FAILED')).toBe(true);
    expect(taskRestartable('UNIT', 'SUCCEEDED')).toBe(false);
    expect(['SUCCEEDED', 'WARNING', 'FAILED', 'SKIPPED'].every((status) => taskRestartable('TASK', status))).toBe(true);
    expect(taskRestartable('BATCH', 'AWAITING_CHILDREN')).toBe(false);
  });

  it('родитель принимает перезапуск детей, пока не раскрывается и не собирается', () => {
    expect(acceptsChildRestart('WARNING')).toBe(true);
    expect(acceptsChildRestart('AWAITING_CHILDREN', 'COLLECT')).toBe(true);
    expect(acceptsChildRestart('PENDING', 'COLLECT')).toBe(true);
    expect(acceptsChildRestart('PENDING', 'EXPAND')).toBe(false);
    expect(acceptsChildRestart('RUNNING', 'COLLECT')).toBe(false);
  });
});
