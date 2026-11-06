# Быстрый старт

_Что здесь: от пустого проекта до выполненной задачи. Читатель не знает ничего, кроме Spring Boot._

## Требования

_Java 25 (JDK 25.0.4+), Spring Boot 4.1, PostgreSQL — минимальная версия и почему именно PostgreSQL: партиционирование, SKIP LOCKED, advisory-блокировки._

## Подключение

_Gradle и Maven: одна зависимость на стартер; почему стартер не экспортирует BOM Spring Boot и что это значит для ваших версий._

## Что приезжает с зависимостью

_обязательные транзитивы: spring-boot-starter, spring-boot-starter-jdbc, spring-boot-starter-jackson._

## Необязательные зависимости

_таблица «добавили — получили»: Micrometer → метрики, Actuator → health indicator, Spring MVC → REST, Spring Security → RequestMatcher, jackson-dataformat-xml → SOAP, Liquibase → миграции через changelog._

## Схема базы данных

_три пути: include нашего master.xml в ваш Liquibase, голый SQL в ваш инструмент, initialize=always для локальной разработки; почему схему применяете вы._

## Первая задача

_процессор плоской задачи, постановка через TaskService, как увидеть результат._

## Первая коммуникация

_процессор исходящей REST-коммуникации и send._

## Проверить, что всё работает

_что пишется в лог при старте, health indicator, таблицы tc_*._

## Что дальше

_ссылки на понятия, задачи, коммуникации, настройки._
