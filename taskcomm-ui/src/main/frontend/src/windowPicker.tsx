import { ClockCircleOutlined, DownOutlined } from '@ant-design/icons';
import { Button, DatePicker, Divider, Flex, InputNumber, Popover, Select, Space, Tooltip, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import 'dayjs/locale/ru';
import { useState } from 'react';
import { useI18n } from './i18n';
import { useMeta } from './meta';
import { QUICK_RANGES, quickLabel, useWindowChoice, type WindowChoice, type WindowUnit } from './windowChoice';

// Выбор окна в шапке (docs/guide/api/ui.md): быстрые диапазоны, «последние N» и отрезок по календарю.
// Окно — по времени создания дела, а не сущности; подсказка об этом — внизу панели.

type Translate = ReturnType<typeof useI18n>['t'];

/** Окно экземпляра вида PT720H — тем же видом, что относительный выбор. */
function instanceWindow(searchWindow?: string): WindowChoice | undefined {
  const hours = /^PT(\d+)H$/.exec(searchWindow ?? '');
  if (!hours) {
    return undefined;
  }
  const value = Number(hours[1]);
  return value % 24 === 0
    ? { kind: 'relative', amount: value / 24, unit: 'd' }
    : { kind: 'relative', amount: value, unit: 'h' };
}

/** Отрезок короче: год пишется, только если он не текущий. */
function moment(value: string): string {
  const at = dayjs(value);
  return at.format(at.year() === dayjs().year() ? 'DD.MM HH:mm' : 'DD.MM.YYYY HH:mm');
}

/** Подпись выбора в шапке. */
export function windowLabel(choice: WindowChoice, t: Translate, searchWindow?: string): string {
  switch (choice.kind) {
    case 'everywhere':
      return t('window.everywhere');
    case 'absolute':
      return `${moment(choice.from)} — ${moment(choice.to)}`;
    case 'relative': {
      const quick = quickLabel(choice);
      return quick ? t(quick) : t('window.lastN', { amount: choice.amount, unit: t(`window.unit.${choice.unit}`) });
    }
    default: {
      const instance = instanceWindow(searchWindow);
      return instance ? windowLabel(instance, t) : t('window.label');
    }
  }
}

/** Выбор окна в шапке — для всех выборок сразу. */
export function WindowControl() {
  const { t, language } = useI18n();
  const meta = useMeta();
  const [choice, setChoice] = useWindowChoice();
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState<number | null>(choice.kind === 'relative' ? choice.amount : 2);
  const [unit, setUnit] = useState<WindowUnit>(choice.kind === 'relative' ? choice.unit : 'h');
  dayjs.locale(language === 'ru' ? 'ru' : 'en');

  const apply = (next: WindowChoice) => {
    setChoice(next);
    setOpen(false);
  };
  const range: [Dayjs, Dayjs] | null =
    choice.kind === 'absolute' ? [dayjs(choice.from), dayjs(choice.to)] : null;
  const selected = (candidate: WindowChoice) => JSON.stringify(candidate) === JSON.stringify(choice);

  const content = (
    <Flex vertical gap={8} style={{ width: 360 }} className="window-picker">
      <Typography.Text type="secondary">{t('window.quick')}</Typography.Text>
      <div className="window-quick">
        {QUICK_RANGES.map((quick) => {
          const candidate: WindowChoice = { kind: 'relative', amount: quick.amount, unit: quick.unit };
          return (
            <Button
              key={quick.label}
              size="small"
              type={selected(candidate) ? 'primary' : 'text'}
              onClick={() => apply(candidate)}
            >
              {t(quick.label)}
            </Button>
          );
        })}
        <Button
          size="small"
          type={choice.kind === 'everywhere' ? 'primary' : 'text'}
          onClick={() => apply({ kind: 'everywhere' })}
        >
          {t('window.everywhere')}
        </Button>
      </div>
      <Divider style={{ margin: '4px 0' }} />
      <Typography.Text type="secondary">{t('window.last')}</Typography.Text>
      <Space.Compact>
        <InputNumber
          size="small"
          min={1}
          precision={0}
          value={amount}
          onChange={setAmount}
          aria-label={t('window.amount')}
          style={{ width: 90 }}
        />
        <Select<WindowUnit>
          size="small"
          value={unit}
          onChange={setUnit}
          aria-label={t('window.unitLabel')}
          getPopupContainer={(node: HTMLElement) => node.parentElement ?? document.body}
          style={{ width: 110 }}
          options={(['m', 'h', 'd'] as WindowUnit[]).map((value) => ({ value, label: t(`window.units.${value}`) }))}
        />
        <Button
          size="small"
          type="primary"
          disabled={!amount || amount < 1}
          onClick={() => amount && apply({ kind: 'relative', amount, unit })}
        >
          {t('window.apply')}
        </Button>
      </Space.Compact>
      <Divider style={{ margin: '4px 0' }} />
      <Typography.Text type="secondary">{t('window.absolute')}</Typography.Text>
      <DatePicker.RangePicker
        size="small"
        showTime={{ format: 'HH:mm' }}
        format="DD.MM.YYYY HH:mm"
        value={range}
        allowClear={false}
        // Календарь — внутри панели: иначе щелчок по нему закрывал бы её как щелчок снаружи.
        getPopupContainer={(node: HTMLElement) => node.parentElement ?? document.body}
        onChange={(dates) => {
          const [from, to] = dates ?? [];
          if (from && to && from.isBefore(to)) {
            apply({ kind: 'absolute', from: from.toISOString(), to: to.toISOString() });
          }
        }}
      />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {t('window.hint')}
      </Typography.Text>
    </Flex>
  );

  const label = windowLabel(choice, t, meta.data?.searchWindow);
  return (
    <Popover content={content} trigger="click" placement="bottomRight" open={open} onOpenChange={setOpen}>
      {/* Подсказка — только пока панель закрыта: открытую она бы заслоняла. */}
      <Tooltip title={`${t('window.label')}: ${label}`} placement="bottomRight" open={open ? false : undefined}>
        <Button size="small" icon={<ClockCircleOutlined />} aria-label={t('window.label')}>
          <span className="window-label">{label}</span>
          <DownOutlined style={{ fontSize: 10 }} />
        </Button>
      </Tooltip>
    </Popover>
  );
}
