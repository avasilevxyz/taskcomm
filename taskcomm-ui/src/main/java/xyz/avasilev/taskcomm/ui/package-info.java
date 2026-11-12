/**
 * Веб-интерфейс taskcomm (docs/adr/0149-web-interface-module.md): автоконфигурация модуля taskcomm-ui.
 * Интерфейс появляется в servlet-приложении с включённым REST стартера и выключается свойством
 * {@code taskcomm.ui.enabled}; путь — {@code taskcomm.ui.base-path}.
 */
package xyz.avasilev.taskcomm.ui;
