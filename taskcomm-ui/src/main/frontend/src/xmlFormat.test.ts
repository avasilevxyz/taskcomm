import { describe, expect, it } from 'vitest';
import { formatXml, present } from './xmlFormat';

describe('отступы XML', () => {
  it('одна строка — с отступами по вложенности, с объявлением, атрибутами и CDATA', () => {
    const soap =
      '<?xml version="1.0" encoding="UTF-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">' +
      '<soap:Body><pay id="A-1" note="a&amp;b"><amount>100</amount><memo><![CDATA[<raw>]]></memo><empty/></pay></soap:Body>' +
      '</soap:Envelope>';
    expect(formatXml(soap)).toBe(
      [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">',
        '  <soap:Body>',
        '    <pay id="A-1" note="a&amp;b">',
        '      <amount>100</amount>',
        '      <memo>',
        '        <![CDATA[<raw>]]>',
        '      </memo>',
        '      <empty/>',
        '    </pay>',
        '  </soap:Body>',
        '</soap:Envelope>',
      ].join('\n'),
    );
  });

  it('многострочный, неразобравшийся и с текстом вперемешку с тегами — как есть', () => {
    expect(formatXml('<a>\n  <b/>\n</a>')).toBe('<a>\n  <b/>\n</a>');
    expect(formatXml('<a><b></a>')).toBe('<a><b></a>');
    expect(formatXml('<p>Сумма <b>100</b> руб.</p>')).toBe('<p>Сумма <b>100</b> руб.</p>');
  });
});

describe('язык тела', () => {
  it('JSON с отступами, XML, текст', () => {
    expect(present({ a: 1 })).toEqual({ text: '{\n  "a": 1\n}', language: 'json' });
    expect(present('{"a":1}')).toEqual({ text: '{\n  "a": 1\n}', language: 'json' });
    expect(present('<a><b/></a>')).toEqual({ text: '<a>\n  <b/>\n</a>', language: 'xml' });
    expect(present('принято банком')).toEqual({ text: 'принято банком', language: 'text' });
  });
});
