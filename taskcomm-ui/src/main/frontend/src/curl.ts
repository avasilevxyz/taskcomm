// Команда curl, повторяющая запрос интерфейса: создание из формы или чтение таблицы. Без секретов:
// ни кук сессии, ни токена CSRF — команду копируют в чаты и тикеты, живая сессия там не нужна.
// Авторизацию добавляют по правилам своего приложения.

/** Строка в одинарных кавычках оболочки: кавычка внутри — как {@code '\''}. */
export function quote(text: string): string {
  return `'${text.replace(/'/g, `'\\''`)}'`;
}

/**
 * Многострочная команда curl: метод, адрес, заголовки и тело.
 *
 * @param method метод, по умолчанию POST
 * @param url полный адрес запроса
 * @param body тело или {@code undefined} — без тела
 * @param contentType тип тела; без тела не нужен
 */
export function curlCommand({ method = 'POST', url, body, contentType = 'application/json' }: {
  method?: 'GET' | 'POST';
  url: string;
  body?: string;
  contentType?: string;
}): string {
  const lines = [`curl --request ${method} ${quote(url)}`, `  --header ${quote('Accept: application/json')}`];
  if (body !== undefined) {
    lines.push(`  --header ${quote(`Content-Type: ${contentType}`)}`);
    lines.push(`  --data-raw ${quote(body)}`);
  }
  return lines.join(' \\\n');
}
