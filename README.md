# Pixel Rush

Учебный проект группы 11-400 по пакетной обработке изображений. Общие контракты позволяют подключать фильтры и движки через Spring.

## Сборка

Нужны JDK 21, Maven и Make.

```sh
make test
make build
```

## Контракты

- `ImageData` хранит растровые данные в формате ARGB, по строкам слева направо. `pixels()` возвращает копию. Для чтения в горячем цикле используйте `argbAt(y * width + x)`.
- `FilterStep` хранит имя фильтра и неизменяемую карту параметров. `FilterRegistry.require(name)` находит зарегистрированный компонент. Имена фильтров уникальны.
- `ImageFilter.applyRegion` читает исходное изображение и пишет только в строки `[startY, endY)` полноразмерного массива назначения. Один компонент может вызываться из нескольких потоков.
- Фильтр с настройками перечисляет их имена в `ImageFilter.parameterNames()`. Для фильтров, которым выгодны области на всю высоту кадра, ForkJoin поддерживает `ColumnTiledFilter`.
- `ProcessingEngine.processBatch` принимает изображения и цепочку фильтров в памяти. Результаты идут в том же порядке, что и входные изображения. Дисковый ввод и вывод выполняются отдельно.

## Последовательная обработка

```sh
make run-seq INPUT=./images OUTPUT=./out RADIUS=2 SIGMA=1.0
```

`run-seq` использует общий CLI и применяет Gaussian Blur к PNG, JPEG и BMP из входного каталога. Подкаталоги и символические ссылки пропускаются. Входной и выходной каталоги должны различаться. Результаты предыдущего запуска с теми же именами заменяются. Если два результата текущего запуска записываются по одному пути, операция останавливается и первый результат сохраняется. PNG сохраняет альфа-канал, JPEG и BMP записываются в RGB. JPEG с EXIF-ориентацией приводится к отображаемому положению до фильтрации.

Радиус задаётся целым числом от 0 до 1024, `sigma` — конечным положительным числом. По умолчанию `radius=2`, `sigma=1.0`. На границах фильтр повторяет крайние пиксели. JVM получает `-Xmx4g`, значение можно переопределить через `JVM_ARGS`. Входная и выходная коллекции хранятся в памяти во время пакетного запуска.

## Бенчмарки и проверка корректности

```sh
make benchmark
make benchmark CSV=./benchmark-results.csv THREADS=1,2,4,8 FILTERS=gaussian,sobel,median,sharpen,motion COUNT=8 WIDTH=256 HEIGHT=256
```

`benchmark` прогревает оба режима измерения и сохраняет CSV со средним временем вычислений в памяти (`compute_ms`) и полного цикла (`total_ms`). При заданном `INPUT` полный цикл читает оригинальные файлы. Без `INPUT` бенчмарк генерирует PNG с фиксированным зерном 42.

Каждая измеряемая итерация сверяется с последовательным эталоном за пределами таймера. Для полного цикла сверяются растры перед кодированием. `max_diff` и `mae` показывают наибольшие значения среди всех измеренных итераций. Для партии с разными размерами в CSV указываются `width=0` и `height=0`. Ускорение считается как отношение времени последовательного запуска к времени параллельного, эффективность — как ускорение, делённое на запрошенное число потоков.

Воспроизведение серий из [отчёта](report.pdf) и [презентации](presentation.pdf):

```sh
make benchmark CSV=./benchmark-batch.csv FILTERS=gaussian,median THREADS=1,2,4,8,16 COUNT=12 WIDTH=512 HEIGHT=384 WARMUP=2 ITERATIONS=3
make benchmark CSV=./benchmark-large.csv FILTERS=gaussian,median THREADS=1,2,4,8,16 COUNT=1 WIDTH=2048 HEIGHT=1536 WARMUP=2 ITERATIONS=3
```

Исходные замеры сохранены в [batch.csv](docs/benchmark/batch.csv) и [large.csv](docs/benchmark/large.csv).

## Пакетный конвейер

```sh
make run-par INPUT=./images OUTPUT=./out MODE=forkjoin THREADS=4 PIPELINE=median:radius=1,gaussian:radius=2:sigma=1,sharpen
```

`run-par` по умолчанию использует `threadpool`, 4 потока и фильтр `gaussian`. Для запуска без Make соберите JAR командой `mvn package`.

```sh
java -Xmx4g -jar target/pixel-rush-0.1.0-SNAPSHOT.jar --batch "--input=./images" "--output=./out" --mode=forkjoin --threads=4 "--pipeline=median:radius=1,motion:radius=3:angle=45,sharpen"
```

Конвейер выполняет фильтры в порядке перечисления. Шаг записывается как `фильтр:параметр=значение:параметр=значение`. Шаги разделяются запятой. CLI принимает режимы `sequential`, `threadpool`, `forkjoin`. У JAR с флагом `--batch` по умолчанию выбран `sequential`, 1 поток и `gaussian`. Пути задаются через `--input` и `--output`.

| Фильтр | Параметры |
| --- | --- |
| `gaussian` | `radius`, `sigma` |
| `sobel` | `scale`, `threshold` |
| `median` | `radius` |
| `sharpen` | `amount` |
| `motion` | `radius`, `angle` |

CLI и REST возвращают ошибку, если в цепочке указано неизвестное имя параметра.

Фильтр `motion` усредняет RGB по `2 × radius + 1` отсчётам вдоль линии движения. `radius` принимает целые значения от 0 до 1024 и равен 3 по умолчанию. `angle` измеряется в градусах от горизонтали изображения и равен 0 по умолчанию. Угол 90° задаёт вертикальное размытие. На границах повторяется крайний пиксель. Альфа-канал берётся из центрального пикселя.

## REST и Swagger

Запустите `mvn spring-boot:run` и откройте [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). Описание OpenAPI доступно по пути `/v3/api-docs`. Через `POST /api/images/process` можно загрузить один PNG, JPEG или BMP в multipart-поле `file`. Ответ содержит обработанный PNG. Параметры `pipeline`, `mode` и `threads` совпадают с параметрами CLI. Максимальный размер файла 20 МБ, максимальное число пикселей 16 777 216.

Для демонстрации параллелизма внутри одного изображения используйте `forkjoin` в REST. Для параллельной обработки нескольких файлов используйте `make run-par MODE=threadpool` с входным каталогом.

```sh
curl -f -F "file=@images/example.png" "http://127.0.0.1:8080/api/images/process?pipeline=motion:radius=3:angle=45&mode=forkjoin&threads=4" -o processed.png
```

## CI

GitHub Actions запускает `mvn test` на JDK 21 для каждого pull request.
