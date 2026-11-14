import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { JsonBlock } from './common';
import { I18nProvider } from './i18n';
import { PanelStackProvider } from './panels';

function block() {
  return render(
    <MemoryRouter>
      <I18nProvider language="ru">
        <PanelStackProvider>
          <JsonBlock value={{ orderId: 'A-1' }} title="Нагрузка" />
        </PanelStackProvider>
      </I18nProvider>
    </MemoryRouter>,
  );
}

describe('блок кода', () => {
  it('«Развернуть» открывает его в своей панели с названием блока', () => {
    block();
    fireEvent.click(screen.getByRole('button', { name: 'Развернуть' }));
    expect(screen.getByRole('dialog', { name: 'Нагрузка' })).toBeTruthy();
  });

  it('«Копировать» кладёт текст как он показан — JSON с отступами, без номеров строк', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    block();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Копировать' }));
    });
    expect(writeText).toHaveBeenCalledWith('{\n  "orderId": "A-1"\n}');
  });
});
