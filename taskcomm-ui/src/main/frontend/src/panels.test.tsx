import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { I18nProvider } from './i18n';
import { PanelStackProvider, useOpenPanel } from './panels';

function Opener({ name }: { name: string }) {
  const open = useOpenPanel();
  const [clicks, setClicks] = useState(0);
  return (
    <>
      <button type="button" onClick={() => open({ kind: 'entity', label: name, render: () => <Opener name={`${name}+`} /> })}>
        {`открыть ${name}`}
      </button>
      <button type="button" onClick={() => setClicks(clicks + 1)}>{`счёт ${name}: ${clicks}`}</button>
    </>
  );
}

function stack() {
  return render(
    <MemoryRouter>
      <I18nProvider language="ru">
        <PanelStackProvider>
          <Opener name="A" />
        </PanelStackProvider>
      </I18nProvider>
    </MemoryRouter>,
  );
}

const labels = () => screen.queryAllByRole('dialog').map((panel) => panel.getAttribute('aria-label'));
const width = window.innerWidth;

afterEach(() => {
  window.innerWidth = width;
  window.localStorage.clear();
});

describe('стопка панелей', () => {
  it('панель из панели — рядом; из левой — заменяет правые; Esc закрывает правую', () => {
    window.innerWidth = 3000;
    stack();
    fireEvent.click(screen.getByText('открыть A'));
    fireEvent.click(screen.getByText('открыть A+'));
    expect(labels()).toEqual(['A', 'A+']);

    fireEvent.click(screen.getByText('открыть A+'));
    expect(labels()).toEqual(['A', 'A+']);

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(labels()).toEqual(['A']);
  });

  it('не помещается — старая сворачивается в полосу, щелчок раскрывает её', () => {
    window.innerWidth = 1000;
    stack();
    fireEvent.click(screen.getByText('открыть A'));
    fireEvent.click(screen.getByText('открыть A+'));
    expect(labels()).toEqual(['A+']);

    fireEvent.click(screen.getByRole('button', { name: 'Раскрыть: A' }));
    expect(labels()).toEqual(['A']);
    expect(screen.getByRole('button', { name: 'Раскрыть: A+' })).toBeTruthy();
  });

  it('свёрнутая панель не выгружается: после раскрытия её состояние на месте', () => {
    window.innerWidth = 1000;
    stack();
    fireEvent.click(screen.getByText('открыть A'));
    fireEvent.click(screen.getByText('счёт A+: 0'));
    fireEvent.click(screen.getByText('счёт A+: 1'));
    fireEvent.click(screen.getByText('открыть A+'));
    expect(labels()).toEqual(['A+']);

    fireEvent.click(screen.getByRole('button', { name: 'Раскрыть: A' }));
    expect(labels()).toEqual(['A']);
    expect(screen.getByText('счёт A+: 2')).toBeTruthy();
  });

  it('«Свернуть» делает полосой, даже когда место есть; щелчок по полосе раскрывает', () => {
    window.innerWidth = 3000;
    stack();
    fireEvent.click(screen.getByText('открыть A'));
    fireEvent.click(screen.getByText('открыть A+'));
    expect(labels()).toEqual(['A', 'A+']);

    fireEvent.click(screen.getAllByRole('button', { name: 'Свернуть' })[0]);
    expect(labels()).toEqual(['A+']);

    fireEvent.click(screen.getByRole('button', { name: 'Раскрыть: A' }));
    expect(labels()).toEqual(['A', 'A+']);
  });

  it('свёрнуты все — нет подложки, страница прокручивается, Esc не закрывает; раскрыта одна — всё как было', () => {
    window.innerWidth = 3000;
    stack();
    const backdrop = () => document.querySelector('.panels-backdrop');
    fireEvent.click(screen.getByText('открыть A'));
    fireEvent.click(screen.getByText('открыть A+'));
    expect(backdrop()).not.toBeNull();
    expect(document.body.style.overflow).toBe('hidden');

    fireEvent.click(screen.getAllByRole('button', { name: 'Свернуть' })[1]);
    expect(backdrop()).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Свернуть' }));
    expect(labels()).toEqual([]);
    expect(backdrop()).toBeNull();
    expect(document.body.style.overflow).toBe('');

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getAllByRole('button', { name: /^Раскрыть: / })).toHaveLength(2);

    fireEvent.click(screen.getByRole('button', { name: 'Раскрыть: A' }));
    expect(labels()).toEqual(['A']);
    expect(backdrop()).not.toBeNull();
    expect(document.body.style.overflow).toBe('hidden');
  });
});
