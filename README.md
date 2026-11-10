# quasar-openio

Открытый системный демон `quasar_openio` для Yandex Station Max (`YNDX-0008`,
`yandexstation_2`, Android 9 / ARM64).

Демон — **супервизор**: он запускает бинарник плагина с аргументом-режимом,
наблюдает кнопки/громкость/микрофон, управляет состоянием музыки и жизненным
циклом процесса. **Экран и кольцо рисует сам плагин** через `mmap` fb4 и fb5 —
кольцо точно так же, как экран. Демон не ретранслирует кадры и не пишет в
framebuffer, пока плагин загружен. Дополнительно демон умеет
замораживать/размораживать `com.yandex.io.sdk` сигналами и принимает PCM-поток
с выдачей его по loopback HTTP и в pipe визуализатора.

**Важно:** это userspace-демон, а не замена драйверов ядра. Он не отключает и не
удаляет `com.yandex.io.sdk`, не пишет в `/system`, `/vendor`, `/boot`, не меняет
разрешение экрана, не вызывает `FBIOPUT_*`, не использует `system()`/`popen()` и
не запускает shell-команды из обработчиков событий.

## Статус проверки

Реализовано и проверено на host:

- строгая сборка GCC с `-Werror`;
- интеграционные тесты JSON/Unix socket, framebuffer `mmap` со stride/padding,
  input-событий, `SIGSTOP`/`SIGCONT`, восстановления после `SIGKILL` демона,
  PCM→HTTP, ограничения очередей и очистки по `SIGTERM`;
- ASan/UBSan-прогон;
- тесты OTA-скрипта с mock `iptables`/`ip6tables`;
- стандартный плагин `quasar_pack`: часы, визуализатор и 12 анимаций, экран и
  кольцо рисуются самим плагином через mmap fb4/fb5;
- host-тесты плагина: fb4 пишется, fb5 не трогается в idle/визуализаторе,
  анимации сами рисуют и гасят кольцо, padding строк не пишется;
- host-тесты супервизора: контракт запуска, кнопки, музыкальные переходы,
  отказ от `draw_*` при загруженном плагине, пожинание ребёнка;
- статическая PIE cross-сборка ELF64 AArch64 без `INTERP` через `zig cc -target
  aarch64-linux-musl -static-pie` для демона и пака.

**Не проверено на реальной станции в этой среде:** Amlogic LED-драйвер,
SELinux-домен, поведение `io.sdk` при заморозке, совместимость HTTP/WAV со штатным
плеером, Docker-сборка и длительную работу под нагрузкой. Перед развёртыванием
выполните чек-лист ниже.

Вложенный `quasar-daemon-11-05.jpeg` оказался ZIP-архивом с предыдущей реализацией.
Он был распакован и изучен как справочный материал; готовые бинарники из архива не
использовались и не запускались.

## Состав

```text
include/quasar_openio.h     общие структуры и лимиты
src/                        супервизор: main, plugin, IPC, JSON, fb, input, SDK, audio
pack/                       плагин: часы, визуализатор, анимации, mmap fb4+fb5
init/quasar_openio.rc       Android init service для /odm/etc/init
plugins/standard/plugin.conf манифест стандартного плагина
scripts/install_odm.sh      осторожная установка в /odm, по умолчанию dry-run
scripts/quasar_block_ota.sh блокировка OTA-адресов, по умолчанию dry-run
sepolicy/                   справочная SELinux-политика, не загружается автоматически
tools/fb_info.c             read-only probe геометрии framebuffer
docs/PACK.md                документация стандартного плагина
examples/                   Python-примеры клиента и PCM-отправителя
tests/                      host-тесты,fixture-библиотека, mock iptables
Dockerfile                  статическая сборка на arm64v8/alpine
```

## Сборка

Обычная host-сборка:

```sh
make
make test
make sanitize
make fb-info
```

Статическая сборка через Docker, как требует ТЗ:

```sh
# На x86_64-host сначала нужен binfmt/QEMU для linux/arm64.
docker run --privileged --rm \
  tonistiigi/binfmt@sha256:400a4873b838d1b89194d982c45e5fb3cda4593fbfd7e08a02e76b03b21166f0 \
  --install arm64
docker build --platform linux/arm64 -t quasar-openio-builder .
id=$(docker create --platform linux/arm64 quasar-openio-builder)
mkdir -p build/aarch64
docker cp "$id:/quasar_openio" ./build/aarch64/quasar_openio
docker cp "$id:/plugins/standard/quasar_pack" ./build/aarch64/quasar_pack
docker rm "$id"
file build/aarch64/quasar_openio build/aarch64/quasar_pack
# Оба файла: ELF 64-bit ARM aarch64, statically linked.
```

Альтернативная cross-сборка без Docker, проверенная в этой среде:

```sh
python3 -m pip install ziglang==0.13.0
mkdir -p build/aarch64
python3 -m ziglang cc -target aarch64-linux-musl -static-pie -fPIE -std=c11 -O2 \
  -Iinclude -Wall -Wextra -Wpedantic -Wshadow -Wformat=2 \
  -Wconversion -Wsign-conversion -Werror -fstack-protector-strong \
  -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now,-z,noexecstack src/*.c \
  -o build/aarch64/quasar_openio
python3 -m ziglang cc -target aarch64-linux-musl -static-pie -fPIE -std=c11 -O2 \
  -Iinclude -Wall -Wextra -Wpedantic -Wshadow -Wformat=2 \
  -Wconversion -Wsign-conversion -Werror -fstack-protector-strong \
  -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now,-z,noexecstack \
  pack/*.c src/util.c src/framebuffer.c src/json.c \
  -o build/aarch64/quasar_pack
readelf -h build/aarch64/quasar_openio build/aarch64/quasar_pack | grep Machine
readelf -l build/aarch64/quasar_openio build/aarch64/quasar_pack | grep INTERP || true
```

Runtime не требует Python, GStreamer, shell или динамического загрузчика.
Плагин `quasar_pack` открывает fb4 и fb5 сам и пишет только в `mmap`. Демон
запускает его и передаёт кнопки. Подробнее: [`docs/PACK.md`](docs/PACK.md).

## Запуск

Обычный режим ожидает доступ к устройствам и право посылать сигналы `io.sdk`
(в поставляемом init-файле это root):

```sh
./quasar_openio --help
./quasar_openio \
  --socket /data/local/tmp/quasar.sock \
  --socket-mode 0600 \
  --audio-port 19080 \
  --http-port 19081
```

Для проверки протокола без обращения к железу и без сигналов:

```sh
./quasar_openio --dry-run --no-audio --socket /tmp/quasar.sock
```

`--dry-run` не открывает input/framebuffer, не пишет journal и не посылает
`SIGSTOP`/`SIGCONT`. Loopback audio можно оставить включённым или отключить
`--no-audio`.

По умолчанию Unix socket имеет режим `0600`. Для доступа приложений используйте
выделенную группу, а не `0777`:

```sh
./quasar_openio --socket-mode 0660 --socket-gid 12345
```

Android-приложению обычно нужен `android.net.LocalSocket`, подходящий UID/GID и
SELinux-разрешение на подключение к socket-файлу. Один демон на устройство
защищён `flock`-файлом `/data/local/tmp/quasar_openio.lock`.

## Unix JSON API

Протокол: NDJSON, одна JSON-команда на строку, ответ также завершается `\n`.
Поддерживаются только плоские объекты со строковыми полями `action` и `data`.
Неизвестные поля, дубликаты, вложенные объекты, Unicode вне ASCII и команды без
`action` отклоняются. Максимум строки запроса — 4095 байт, клиентов — 8,
ответная очередь клиента — 8192 байта. Медленный клиент отключается, а не
накапливает память.

Команды:

```json
{"action":"pause_sdk"}
{"action":"resume_sdk"}
{"action":"music_state","data":"playing|paused|stopped|idle|auto"}
{"action":"plugin_animation","data":"<mode from the plugin manifest>"}
{"action":"status"}
```

`draw_screen`/`draw_ring` (base64, ровно 1200/72 байта RGB888) остались как
legacy-режим и работают **только** с `--no-plugin`. Если плагин загружен, они
отклоняются с `plugin_owns_output`: рисует плагин, а не демон.

Примеры ответов:

```json
{"ok":true,"signal_requested":true}
{"ok":true,"queued":true}
{"ok":false,"error":"invalid_frame_data"}
{"ok":false,"error":"hardware_unavailable"}
{"ok":false,"error":"sdk_identity_changed"}
{"ok":false,"error":"plugin_owns_output"}
{"ok":false,"error":"invalid_music_state"}
```

`queued:true` означает, что кадр проверен и поставлен в однослотовую очередь
«последний кадр побеждает». Это не подтверждение того, что драйвер физически
вывел кадр. Частота вывода ограничена примерно 58 кадрами/с на каждый framebuffer.

События рассылаются всем подключённым Unix-клиентам:

```json
{"event":"button_alice_pressed","source":"panel","code":582}
{"event":"button_alice_released","source":"panel","code":582}
{"event":"microphone_mute_on","source":"panel","code":14}
{"event":"microphone_mute_off","source":"panel","code":14}
{"event":"volume_up","source":"rotary","code":104}
{"event":"volume_down","source":"rotary","code":109}
{"event":"volume_up","source":"remote","code":115}
{"event":"button_power_pressed","source":"remote","code":116}
```

Демон только наблюдает `/dev/input/event*` через `poll()` и не делает
`EVIOCGRAB`, поэтому Android и `io.sdk` продолжают получать те же события.
Перед открытием проверяется имя устройства через `EVIOCGNAME`; неверный event-узел
не читается. Громкость и микрофон штатно не перехватываются, чтобы не было
двойного управления.

Пример клиента:

```sh
python3 examples/client.py --socket /data/local/tmp/quasar.sock status
python3 examples/client.py pause
python3 examples/client.py music-state playing
python3 examples/client.py animation volume-ring
python3 examples/client.py listen
```

## Framebuffer

- Экран: `/dev/graphics/fb4`, 25×16, входной кадр ровно 1200 байт RGB888.
- Кольцо: `/dev/graphics/fb5`, 24×1, входной кадр ровно 72 байта RGB888.

Перед выводом демон читает `FBIOGET_FSCREENINFO` и `FBIOGET_VSCREENINFO` и
отказывается работать, если геометрия/формат не соответствуют ожидаемым.
`stride` берётся из `fix.line_length`, а не предполагается равным 75/72. Padding
строк не записывается. Поддерживаются оба типичных порядка байт 24-bit truecolor:
RGB и BGR, согласно `red/green/blue.offset`. Запись идёт только в `mmap()`-память;
`write()`, `msync()`, pan и смена видеорежима не используются.

Read-only проверка перед первым выводом:

```sh
./fb_info /dev/graphics/fb4
./fb_info /dev/graphics/fb5
```

Ожидаемые значения для этого ТЗ: `25x16/24bpp stride>=75` и `24x1/24bpp
stride>=72`. Если драйвер сообщает другую геометрию, демон не пытается её
исправить. `SIGSTOP` замораживает процесс `io.sdk`, но **не закрывает его mmap и
file descriptors**; одновременный вывод возможен только если драйвер это
допускает. При сомнении сначала проверьте поведение на одном кадре минимальной
яркости.

## Управление io.sdk

`pause_sdk` находит ровно один процесс с первым аргументом cmdline
`com.yandex.io.sdk`, проверяет `/proc/<pid>/stat` starttime и посылает `SIGSTOP`
напрямую. `resume_sdk` повторно проверяет PID/starttime/cmdline и посылает
`SIGCONT`. Если процесс уже остановлен не нами, демон не принимает его на себя и
не размораживает автоматически. Если найдено несколько точных совпадений, команда
завершается ошибкой `ambiguous_sdk_process`.

Перед `SIGSTOP` пишется root-only journal
`/data/local/tmp/quasar_openio.sdk`. После аварийного завершения демона следующий
запуск возобновляет только тот процесс, который совпадает с journal по
`boot_id`, PID, starttime и package. При штатном `SIGTERM`/`SIGINT` демон сам
посылает `SIGCONT`, закрывает клиенты и устройства, удаляет socket и выходит.

Ограничения:

- `SIGSTOP` замораживает сервисы внутри процесса, поэтому Alice и другие функции
  `io.sdk` могут стать недоступны до `resume_sdk`.
- В Linux 4.9 нет `pidfd`; остаётся узкое окно PID-reuse между проверкой и
  `kill()`. Starttime и повторная проверка уменьшают риск, но не устраняют его
  математически полностью.
- Демон не использует `pm disable`, `pm uninstall`, `am force-stop` и не пишет в
  PackageManager.

## Стандартный плагин

`quasar_openio` только запускает бинарник и управляет кнопками и режимами.
Плагин рисует **и экран, и кольцо сам**: mmap fb4 (25×16) и mmap fb5 (24×1)
через один и тот же валидатор геометрии и `stride`. Никакой ретрансляции кадров
через демон нет.

Плагины считаются доверенными и намеренно работают с root UID в том же
SELinux-домене, что и демон: прямой доступ к экрану, кольцу и другим устройствам
является частью плагинного контракта. Root не отменяет SELinux enforcing, поэтому
доступ к device nodes всё равно должен быть явно разрешён политикой.

```text
quasar_pack MODE --screen /dev/graphics/fb4 --ring /dev/graphics/fb5 \
  --fps 30 --brightness 64 --duration-ms 900 [--audio-fd 3] [--no-ring]
```

Кольцо используется **только в анимациях**. Часы, любой idle-контент и
визуализатор запускаются с `--no-ring` и вообще не открывают fb5. Анимация гасит
кольцо сама перед выходом.

Обязательный набор: `music-play`, `music-pause`, `mic-on`, `mic-off`,
`volume-up`, `volume-down`, `volume-ring`. Дополнительно: `plasma`, `rainbow`,
`starfield`, `pulse`, `rain`.

Логика музыки: старт → `music-play` → `visualizer`; пауза → `music-pause` →
`clock`; конец музыки или тишина дольше 1500 мс → `clock`. Интро `music-play`
не прерывается громкостью и микрофоном.

Кнопки: демон наблюдает input без `EVIOCGRAB`, рассылает события Unix-клиентам,
передаёт их плагину в stdin строками `button <event>` и поднимает стандартные
анимации для громкости и микрофона.

Манифест `plugins/standard/plugin.conf` ставится в
`/odm/lib/quasar/plugins/standard/`. Отдельного init-сервиса для плагина нет.
Полное описание: [`docs/PACK.md`](docs/PACK.md).

## Аудиомост

Вход: TCP **только** `127.0.0.1:19080`, сырой PCM `S16LE`, 48000 Hz, 2 канала,
frame alignment 4 байта. Один producer; второй отклоняется. Кольцевой буфер —
262144 байта (~1.36 с). При переполнении старые кадры вытесняются, память не
растёт. Producer без данных 30 с отключается.

Loopback не аутентифицирует Android UID. До отдельного Unix socket с
`SO_PEERCRED` порты нужно ограничивать SELinux-политикой доверенного клиента или
отключать через `--no-audio`; подробнее в [`docs/AUDIO.md`](docs/AUDIO.md).

Выход: HTTP **только** `127.0.0.1:19081`:

- `GET /audio.wav` — потоковый WAV/PCM с неизвестной длиной (`0xffffffff` в
  RIFF/data полях);
- `GET /audio.pcm` — сырой S16LE;
- `GET /health` — JSON-состояние аудио.

Один streaming-consumer; остальные получают 503. HTTP-запросы ограничены, тело и
pipelining запрещены. Пример отправки:

```sh
python3 examples/pcm_send.py audio.raw
curl --no-buffer http://127.0.0.1:19081/audio.wav > out.wav
```

**Это транспорт, а не доказанное внедрение в `appsrc name=stream-src`.** Объект
GStreamer `appsrc` находится внутри процесса `io.sdk`; внешний процесс не может
получить его просто по имени. Совместимость штатного плеера с loopback HTTP/WAV
нужно проверять на устройстве. Подробная архитектура и варианты внедрения:
[`docs/AUDIO.md`](docs/AUDIO.md).

## Init, /odm и SELinux

Файл `init/quasar_openio.rc` предназначен для `/odm/etc/init/quasar_openio.rc` с
контекстом `u:object_r:vendor_configs_file:s0`. Сервис запускается по
`sys.boot_completed=1`, имеет `disabled` + `oneshot`, чтобы ошибка запуска не
создала restart-loop. После устранения причины можно запустить вручную:

```sh
setprop ctl.start quasar_openio
```

Установка по умолчанию только проверяет и печатает план:

```sh
sh scripts/install_odm.sh --preview
```

Применение требует явного подтверждения, что `/odm` не покрыт dm-verity/AVB:

```sh
sh scripts/install_odm.sh --apply --confirm-odm-unprotected
```

Скрипт не делает remount, не отключает verity, не меняет PackageManager и не
запускает сервис. Он проверяет, что `/odm` — отдельный rw mount ext4/f2fs, и
ставит контексты. Перед preview/apply он также проверяет через `readelf`, что
оба бинарника — статические ELF64 AArch64 без `INTERP`; по умолчанию берутся
`build/aarch64/quasar_openio` и `build/aarch64/quasar_pack`. **Флаг `rw` сам по
себе не доказывает отсутствие verity.** Работающий сервис перед заменой файлов
нужно остановить. Файлы сначала staging-уются, старые версии сохраняются до
`sync`, а при ошибке команд выполняется rollback. Оставшиеся `.old`/`.new`
означают прерванную установку и требуют ручной проверки перед повтором.

RC использует домен `u:r:quasar_openio:s0`, которого нет в стоковой политике.
Справочные файлы лежат в `sepolicy/`; они должны быть адаптированы под реальные
контексты устройства и загружены до старта. Не используйте `setenforce 0` как
решение. Если политика не загружена, init/демон должны fail-closed, а не работать
в обход SELinux.

## Блокировка OTA

Скрипт по умолчанию ничего не меняет:

```sh
sh scripts/quasar_block_ota.sh --preview
sh scripts/quasar_block_ota.sh --apply
sh scripts/quasar_block_ota.sh --status
sh scripts/quasar_block_ota.sh --remove
```

Блокируются адреса:

- `download.cdn.yandex.net`
- `updater.mobile.yandex.net`
- `quasar.s3.yandex.net`

Правила ставятся в OUTPUT для IPv4 и IPv6. Если IPv6 отключён в ядре, можно явно
использовать `--ipv4-only`; иначе отсутствие `ip6tables` считается ошибкой.
Новая цепочка заполняется до установки jump, старая удаляется после; при ошибке
новые цепочки откатываются. Скрипт удаляет только цепочки с маркером
`quasar-openio-ota-v1`.

Важное ограничение: `iptables` резолвит домены один раз. CDN-адреса меняются и
могут быть общими с другими сервисами Яндекса. Это не постоянный DNS-фильтр.
Более надёжный вариант — блокировать проверенный UID обновлятора:

```sh
sh scripts/quasar_block_ota.sh --apply --uid 10xxx
```

Существующие соединения и уже скачанные пакеты скрипт не удаляет.

## Тесты

```sh
make test       # GCC strict + демон + OTA mocks + плагин + супервизор
make sanitize   # ASan/UBSan для всех наборов
```

`tests/libfixture.c` подменяет ioctl и `kill()` только на host. Эту библиотеку
нельзя копировать на станцию. Тесты проверяют в том числе:

- отказ от неверной геометрии framebuffer и сохранение padding;
- RGB/BGR преобразование по offset;
- canonical base64 и точные размеры 1200/72;
- отсутствие двойного volume-события на release кольцевой кнопки;
- PID/starttime/cmdline при pause/resume и crash recovery;
- bounded audio overflow, тайм-аут пустого/медленного HTTP consumer и
  slow-client disconnect;
- обработку всех уже прочитанных NDJSON-команд, включая пакет больше 8 строк;
- отсутствие `system()`/`popen()` и framebuffer `write()` в production-коде;
- rollback OTA-правил и отказ от flush чужих цепочек;
- плагин сам пишет fb4 и fb5, idle/визуализатор не трогают кольцо, анимация
  гасит кольцо при выходе, padding строк не пишется, отказ при неверной геометрии;
- отсутствие CPU-spin после `POLLHUP` PCM pipe и утечки унаследованных fd с
  номерами выше 127 в плагин;
- супервизор передаёт пути обоих устройств и `--no-ring` для idle, не пишет в
  fb5 сам, отклоняет `draw_*`, отрабатывает музыкальные переходы и кнопки.

## Чек-лист перед развёртыванием на станции

1. Сделать read-only inventory: `getevent -lp`, `ls -Z /dev/input/event*
   /dev/graphics/fb*`, `ps -Z | grep com.yandex.io.sdk`, состояние mounts/verity.
2. Собрать статические AArch64-бинарники демона и плагина, проверить `readelf -h/-l`.
3. Проверить `fb_info` для fb4/fb5 без записи.
4. Запустить `--dry-run --no-audio` и проверить JSON-клиентом команды/ошибки.
5. Адаптировать и загрузить SELinux-политику; убедиться, что нет permissive-обхода.
6. Установить в `/odm` только после независимой проверки verity-покрытия.
7. Первый реальный кадр выводить после `pause_sdk`, с минимальной яркостью;
   сразу проверить `resume_sdk`, кнопки, микрофоны и отсутствие kernel warnings.
8. Отдельно проверить audio-потребителя: штатный плеер может не принять
   streaming WAV; при необходимости использовать архитектуру из `docs/AUDIO.md`.
9. Провести soak-тест с ограничением логов и наблюдением за memory/fd count.

## Лицензия

GPL-3.0-or-later, см. `LICENSE`.
