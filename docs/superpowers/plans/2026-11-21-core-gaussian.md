# Core & Sequential Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans and superpowers:test-driven-development to execute the checklist.

**Goal:** Опубликовать каркас в `main` и готовую работу участника 1 в `feat/core-gaussian`.

**Architecture:** Spring регистрирует фильтры и движки как компоненты. Движок принимает изображения в памяти, файловый ввод и вывод вызываются отдельно.

**Tech Stack:** Java 21, Maven, Spring Boot 4.0, JUnit, JDK ImageIO.

**Spec:** [Дизайн](../specs/2026-11-21-core-gaussian-design.md)

## Общие ограничения

Корневой пакет `com.team.imgproc`. Пиксели ARGB хранятся по строкам. Диапазон `[startY, endY)` задаёт единственные строки, которые фильтр вправе записывать.

## Проверки крайних случаев

- Мутация массива после создания изображения и мутация параметров после создания шага.
- Файл с поддерживаемым расширением, который ImageIO не может прочесть, и попытка сохранить изображение с путём вместо имени.
- Полоса у края, полоса из одной строки, радиус больше размеров изображения.
- Повторяющиеся имена фильтров, неизвестный фильтр и порядок шагов.
- Отказ при совпадении входного и выходного каталога.
- Регрессии из ревью: чрезмерный радиус, символическая ссылка на выходной файл и совпадение имён на файловой системе назначения.

## Задачи

- [x] **Каркас.** Добавить `pom.xml`, `Makefile` и короткий README. Написать `CoreContractsTest` и запустить его до реализации контрактов. Реализовать `PixelRushApplication`, `model/{ImageData,FilterStep}`, `filter/{ImageFilter,FilterRegistry}` и `engine/ProcessingEngine`. Проверить `make test`, `make build` и опубликовать корневой коммит в `main`.
- [x] **Файлы.** Добавить `ImageFileIOTest` с проверками PNG/JPEG/BMP, порядка, повреждённого файла и безопасного имени. Запустить RED, реализовать `io/ImageFileIO` и проверить GREEN.
- [x] **Обработка.** Добавить `SequentialEngineTest`, `GaussianBlurFilterTest` и `SequentialDemoTest`. Сверить фильтр с прямой 2D-свёрткой и параллельными полосами. После RED реализовать `engine/SequentialEngine`, `filter/GaussianBlurFilter` и `demo/SequentialDemo`. Подключить демонстрацию в `Makefile`.
- [x] **Проверка и публикация.** Запустить `make test`, `make build`, `make run-seq` и сопоставить результат с критериями дизайна. Проверить diff, закоммитить работу и опубликовать `feat/core-gaussian`.
