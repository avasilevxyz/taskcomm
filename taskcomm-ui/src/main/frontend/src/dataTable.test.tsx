import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { by } from './common';
import { arrange, ColumnsSlotProvider, DataTable, moved, nextSort, useColumnsSlot, visible } from './dataTable';
import { MemoryRouter } from 'react-router';
import { I18nProvider } from './i18n';
import { PanelStackProvider } from './panels';

describe('порядок сортировки', () => {
  it('новый столбец — в конец, оставшиеся — на местах, снятые — долой', () => {
    const first = nextSort([], [{ key: 'a', order: 'ascend' }]);
    expect(first).toEqual([{ key: 'a', order: 'ascend' }]);
    const second = nextSort(first, [
      { key: 'b', order: 'descend' },
      { key: 'a', order: 'ascend' },
    ]);
    expect(second).toEqual([
      { key: 'a', order: 'ascend' },
      { key: 'b', order: 'descend' },
    ]);
    expect(nextSort(second, [{ key: 'b', order: 'descend' }, { key: 'a', order: undefined }])).toEqual([
      { key: 'b', order: 'descend' },
    ]);
  });
});

interface Row {
  id: string;
  group: number;
  name: string;
}

const ROWS: Row[] = [
  { id: '1', group: 2, name: 'б' },
  { id: '2', group: 1, name: 'в' },
  { id: '3', group: 2, name: 'а' },
  { id: '4', group: 1, name: 'а' },
];

function order(): string[] {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[0].textContent ?? '');
}

describe('таблица с сортировкой по нескольким столбцам', () => {
  it('второй щелчок по другому столбцу сортирует внутри равных первого и показывает приоритет', () => {
    render(
      <DataTable<Row>
        rowKey="id"
        pagination={false}
        dataSource={ROWS}
        columns={[
          { title: 'Код', dataIndex: 'id' },
          { title: 'Группа', dataIndex: 'group', sorter: by((row: Row) => row.group) },
          { title: 'Имя', dataIndex: 'name', sorter: by((row: Row) => row.name) },
        ]}
      />,
    );

    fireEvent.click(screen.getByText('Группа'));
    fireEvent.click(screen.getByText('Имя'));

    expect(order()).toEqual(['4', '2', '3', '1']);
    expect(document.querySelectorAll('.sort-priority')).toHaveLength(2);

    fireEvent.click(screen.getByText('Группа'));
    expect(order()).toEqual(['3', '1', '4', '2']);
  });
});

describe('настройки столбцов', () => {
  const keys = ['id', 'group', 'name', 'field.sku'];

  it('сохранённый порядок, новые столбцы — в конце', () => {
    expect(arrange(keys, { order: ['name', 'id', 'gone'], hidden: [] })).toEqual(['name', 'id', 'group', 'field.sku']);
  });

  it('скрытые не видны, но один столбец остаётся всегда', () => {
    expect(visible(keys, { order: [], hidden: ['group', 'field.sku'] })).toEqual(['id', 'name']);
    expect(visible(keys, { order: [], hidden: keys })).toEqual(['id']);
  });

  it('перемещение на место', () => {
    expect(moved(['a', 'b', 'c'], 'c', 0)).toEqual(['c', 'a', 'b']);
    expect(moved(['a', 'b', 'c'], 'a', 1)).toEqual(['b', 'a', 'c']);
    expect(moved(['a', 'b', 'c'], 'b', 9)).toEqual(['a', 'c', 'b']);
  });

  beforeEach(() => window.localStorage.clear());

  it('панель скрывает и двигает столбцы и запоминает это в браузере', () => {
    const table = () => (
      <MemoryRouter>
      <I18nProvider language="ru">
        <PanelStackProvider>
        <DataTable<Row>
          settings="test"
          rowKey="id"
          pagination={false}
          dataSource={ROWS}
          columns={[
            { title: 'Код', dataIndex: 'id' },
            { title: 'Группа', dataIndex: 'group' },
            { title: 'Имя', dataIndex: 'name' },
          ]}
        />
        </PanelStackProvider>
      </I18nProvider>
      </MemoryRouter>
    );
    const headers = () => [...document.querySelectorAll('.ant-table-thead th')].map((cell) => cell.textContent);
    const { unmount } = render(table());

    fireEvent.click(screen.getByRole('button', { name: 'Столбцы' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Группа' }));
    fireEvent.click(screen.getAllByRole('button', { name: 'Выше' })[2]);
    fireEvent.click(screen.getAllByRole('button', { name: 'Выше' })[1]);
    expect(headers()).toEqual(['Имя', 'Код']);

    unmount();
    render(table());
    expect(headers()).toEqual(['Имя', 'Код']);
  });

  it('столбец, брошенный в зону начала, встаёт первым', async () => {
    render(
      <MemoryRouter>
      <I18nProvider language="ru">
        <PanelStackProvider>
        <DataTable<Row>
          settings="edges"
          rowKey="id"
          pagination={false}
          dataSource={ROWS}
          columns={[
            { title: 'Код', dataIndex: 'id' },
            { title: 'Группа', dataIndex: 'group' },
            { title: 'Имя', dataIndex: 'name' },
          ]}
        />
        </PanelStackProvider>
      </I18nProvider>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Столбцы' }));
    const row = screen.getByRole('checkbox', { name: 'Имя' }).closest('.column-setting')!;
    fireEvent.dragStart(row, { dataTransfer: { setData: () => undefined, effectAllowed: '' } });
    const start = await screen.findByText('В начало');
    fireEvent.dragOver(start);
    fireEvent.drop(start);

    expect([...document.querySelectorAll('.ant-table-thead th')].map((cell) => cell.textContent)).toEqual([
      'Имя',
      'Код',
      'Группа',
    ]);
    expect(screen.queryByText('В начало')).toBeNull();
  });

  it('кнопка «Столбцы» — в месте над таблицей; место не сейчас — кнопки нет', () => {
    function Host({ active }: { active: boolean }) {
      const [slot, node] = useColumnsSlot();
      return (
        <MemoryRouter>
          <I18nProvider language="ru">
            <PanelStackProvider>
              <div data-testid="row">{slot}</div>
              <ColumnsSlotProvider node={active ? node : null}>
                <DataTable<Row> settings="slot" rowKey="id" pagination={false} dataSource={ROWS} columns={[{ title: 'Код', dataIndex: 'id' }]} />
              </ColumnsSlotProvider>
            </PanelStackProvider>
          </I18nProvider>
        </MemoryRouter>
      );
    }
    const { rerender } = render(<Host active />);
    expect(within(screen.getByTestId('row')).getByRole('button', { name: 'Столбцы' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Столбцы' })).toHaveLength(1);

    rerender(<Host active={false} />);
    expect(screen.queryAllByRole('button', { name: 'Столбцы' })).toHaveLength(0);
  });
});
