// Отступы для XML, пришедшего одной строкой, — так обычно приходят тела SOAP. Только для показа:
// у XML пробелы между тегами бывают значимыми, поэтому переформатируется лишь документ без текста
// вперемешку с тегами, а всё, что не разобралось, показывается как есть.

const INDENT = '  ';

function escapeText(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttribute(text: string): string {
  return escapeText(text).replace(/"/g, '&quot;');
}

function meaningful(node: Node): boolean {
  return node.nodeType !== Node.TEXT_NODE || (node.textContent ?? '').trim() !== '';
}

/** Текст рядом с дочерними элементами: отступы изменили бы смысл. */
function mixed(element: Element): boolean {
  const children = [...element.childNodes];
  const hasElements = children.some((child) => child.nodeType === Node.ELEMENT_NODE);
  const hasText = children.some((child) => child.nodeType === Node.TEXT_NODE && (child.textContent ?? '').trim() !== '');
  return (hasElements && hasText) || [...element.children].some(mixed);
}

function write(node: Node, depth: number, lines: string[]): void {
  const pad = INDENT.repeat(depth);
  switch (node.nodeType) {
    case Node.ELEMENT_NODE: {
      const element = node as Element;
      const attributes = [...element.attributes].map((attribute) => ` ${attribute.name}="${escapeAttribute(attribute.value)}"`).join('');
      const open = `<${element.tagName}${attributes}`;
      const children = [...element.childNodes].filter(meaningful);
      if (children.length === 0) {
        lines.push(`${pad}${open}/>`);
      } else if (children.length === 1 && children[0].nodeType === Node.TEXT_NODE) {
        lines.push(`${pad}${open}>${escapeText(children[0].textContent ?? '')}</${element.tagName}>`);
      } else {
        lines.push(`${pad}${open}>`);
        children.forEach((child) => write(child, depth + 1, lines));
        lines.push(`${pad}</${element.tagName}>`);
      }
      return;
    }
    case Node.CDATA_SECTION_NODE:
      lines.push(`${pad}<![CDATA[${node.textContent ?? ''}]]>`);
      return;
    case Node.COMMENT_NODE:
      lines.push(`${pad}<!--${node.textContent ?? ''}-->`);
      return;
    case Node.PROCESSING_INSTRUCTION_NODE: {
      const instruction = node as ProcessingInstruction;
      lines.push(`${pad}<?${instruction.target} ${instruction.data}?>`);
      return;
    }
    case Node.TEXT_NODE:
      lines.push(`${pad}${escapeText((node.textContent ?? '').trim())}`);
      return;
    default:
  }
}

/**
 * XML одной строкой — с отступами по вложенности; многострочный, неразобравшийся или с текстом
 * вперемешку с тегами — как есть.
 */
export function formatXml(text: string): string {
  const trimmed = text.trim();
  if (!trimmed.startsWith('<') || trimmed.includes('\n')) {
    return text;
  }
  let document: Document;
  try {
    document = new DOMParser().parseFromString(trimmed, 'application/xml');
  } catch {
    return text;
  }
  if (document.getElementsByTagName('parsererror').length > 0 || !document.documentElement || mixed(document.documentElement)) {
    return text;
  }
  const lines: string[] = [];
  const declaration = /^<\?xml[^?]*\?>/.exec(trimmed);
  if (declaration) {
    lines.push(declaration[0]);
  }
  document.childNodes.forEach((node) => write(node, 0, lines));
  return lines.join('\n');
}

/** Язык тела по содержимому: JSON, XML или просто текст. */
export type CodeLanguage = 'json' | 'xml' | 'sql' | 'shell' | 'text';

/** Текст и язык для показа: JSON — с отступами, XML одной строкой — с отступами, прочее — как есть. */
export function present(value: unknown): { text: string; language: CodeLanguage } {
  if (typeof value !== 'string') {
    return { text: JSON.stringify(value, null, 2), language: 'json' };
  }
  try {
    return { text: JSON.stringify(JSON.parse(value), null, 2), language: 'json' };
  } catch {
    return value.trim().startsWith('<') ? { text: formatXml(value), language: 'xml' } : { text: value, language: 'text' };
  }
}
