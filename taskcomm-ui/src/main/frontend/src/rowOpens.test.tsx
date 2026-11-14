import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DOUBLE_CLICK_WAIT, rowOpens } from './entityPanel';

function Row({ open }: { open: () => void }) {
  return (
    <div data-testid="row" {...rowOpens(open)}>
      <span>01a1d796-de57</span>
      <a href="#x">ссылка</a>
    </div>
  );
}

function select(element: HTMLElement): void {
  const range = document.createRange();
  range.selectNodeContents(element);
  window.getSelection()?.addRange(range);
}

function wait(): void {
  act(() => {
    vi.advanceTimersByTime(DOUBLE_CLICK_WAIT);
  });
}

describe('щелчок по строке таблицы', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    window.getSelection()?.removeAllRanges();
    vi.useRealTimers();
  });

  it('открывает карточку, дождавшись, что второго щелчка не будет', () => {
    const open = vi.fn();
    render(<Row open={open} />);
    fireEvent.click(screen.getByText('01a1d796-de57'), { detail: 1 });
    expect(open).not.toHaveBeenCalled();
    wait();
    expect(open).toHaveBeenCalledOnce();
  });

  it('которым закончили выделять текст строки, — не открывает', () => {
    const open = vi.fn();
    render(<Row open={open} />);
    const text = screen.getByText('01a1d796-de57');
    select(text);
    fireEvent.click(text, { detail: 1 });
    wait();
    expect(open).not.toHaveBeenCalled();
  });

  it('двойной — выделяет слово, а не открывает', () => {
    const open = vi.fn();
    render(<Row open={open} />);
    const text = screen.getByText('01a1d796-de57');
    fireEvent.click(text, { detail: 1 });
    select(text);
    fireEvent.click(text, { detail: 2 });
    wait();
    expect(open).not.toHaveBeenCalled();
  });

  it('по ссылке внутри строки — не открывает', () => {
    const open = vi.fn();
    render(<Row open={open} />);
    fireEvent.click(screen.getByText('ссылка'), { detail: 1 });
    wait();
    expect(open).not.toHaveBeenCalled();
  });
});
