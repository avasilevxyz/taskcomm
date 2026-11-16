--liquibase formatted sql

-- Схема taskcomm 2.3.0 (docs/adr/0167-communication-run-mode.md): режим запуска коммуникации
-- и индексы захвата, которые синхронные коммуникации не читают.
--
-- Только добавления: прежние индексы захвата остаются до следующего выпуска - по ним ходят экземпляры
-- старой версии во время поэкземплярного обновления. Новые индексы строятся по всем партициям
-- tc_communication под блокировкой записи в таблицу (на партиционированной таблице CONCURRENTLY нет) -
-- окно обновления планируется по её размеру.

--changeset taskcomm:2.3.0-01-tc_communication_run_mode
ALTER TABLE tc_communication ADD COLUMN IF NOT EXISTS run_mode text NOT NULL DEFAULT 'ASYNC';

ALTER TABLE tc_communication DROP CONSTRAINT IF EXISTS tc_communication_run_mode_check;
ALTER TABLE tc_communication ADD CONSTRAINT tc_communication_run_mode_check CHECK (run_mode IN ('SYNC', 'ASYNC'));

COMMENT ON COLUMN tc_communication.run_mode IS 'Режим запуска: SYNC - коммуникацию создал и обрабатывает в своём потоке вызывающий, который ждёт ответа здесь и сейчас (синхронная отправка или синхронный приём), ASYNC - её ведёт движок. Синхронную отправку движок не захватывает, фоном не повторяет и вручную не перезапускает - ответ ждать уже некому. Колбэк синхронной обрабатывает движок, как у любой. Свойство запуска, а не самой коммуникации - выбирает вызов, а не процессор';

--changeset taskcomm:2.3.0-02-tc_communication_claim_async
CREATE INDEX IF NOT EXISTS tc_communication_claim_async_deadline_idx
    ON tc_communication (status, (coalesce(source, destination)), claim_order_at)
    WHERE status IN ('PENDING', 'RUNNING') AND (run_mode = 'ASYNC' OR phase = 'HANDLE_RESPONSE');
CREATE INDEX IF NOT EXISTS tc_communication_claim_async_priority_idx
    ON tc_communication (status, (coalesce(source, destination)), priority, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING') AND (run_mode = 'ASYNC' OR phase = 'HANDLE_RESPONSE');
CREATE INDEX IF NOT EXISTS tc_communication_claim_async_fifo_idx
    ON tc_communication (status, (coalesce(source, destination)), next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING') AND (run_mode = 'ASYNC' OR phase = 'HANDLE_RESPONSE');

--changeset taskcomm:2.3.0-03-tc_communication_sync_pending
CREATE INDEX IF NOT EXISTS tc_communication_sync_pending_idx ON tc_communication (next_attempt_at)
    WHERE status = 'PENDING' AND run_mode = 'SYNC';
