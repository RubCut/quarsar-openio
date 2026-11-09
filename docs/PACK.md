# Стандартный плагин: экран и кольцо рисует сам плагин

`quasar_pack` — бинарник стандартного плагина. **Он сам рисует и экран, и кольцо:**
открывает `/dev/graphics/fb4` и `/dev/graphics/fb5`, проверяет геометрию через
`FBIOGET_FSCREENINFO`/`FBIOGET_VSCREENINFO` и пишет кадры **только в `mmap()`**
с реальным `stride` драйвера. Кольцо работает точно так же, как экран — отдельного
механизма, API или ретрансляции для него нет.

`quasar_openio` занимается только:

- запуском бинарника плагина с аргументом-режимом;
- кнопками/ громкостью / микрофоном (наблюдение input и передача событий плагину);
- состоянием музыки и выбором режима (idle / visualizer / анимация);
- жизненным циклом процесса: `SIGTERM` → `SIGKILL`, `waitpid`, backoff, rlimits.

Демон **не рисует** ни fb4, ни fb5, пока плагин загружен: команды `draw_screen` и
`draw_ring` в этом случае отклоняются с `plugin_owns_output`.

## Кольцо — только в анимациях

- `clock` и любой другой idle-режим запускаются с `--no-ring`: устройство fb5
  даже не открывается.
- `visualizer` тоже запускается с `--no-ring`.
- Только анимации получают путь к fb5 и право его мапить.
- Когда анимация заканчивается (по `--duration-ms`, по `SIGTERM` или по смене
  режима), плагин **сам гасит кольцо** нулевым кадром в своём mmap перед выходом.
- Если драйвер сообщает неверную геометрию, плагин отказывается писать
  (`refusing geometry`) и продолжает работать без кольца — fail-closed.

## Обязательный стандартный набор

Манифест не примется, если в нём нет всех семи режимов:

| Режим | Когда запускается | Длительность |
|---|---|---|
| `music-play` | перед визуализатором при старте музыки | 1200 мс |
| `music-pause` | при паузе | 1000 мс |
| `mic-on` | микрофон включён (`microphone_mute_off`) | 800 мс |
| `mic-off` | микрофон выключен (`microphone_mute_on`) | 800 мс |
| `volume-up` | громкость вверх | 900 мс |
| `volume-down` | громкость вниз | 900 мс |
| `volume-ring` | индикация громкости на кольце | 1200 мс |

Дополнительно в паке есть `plasma`, `rainbow`, `starfield`, `pulse`, `rain`.

Порядок при музыке: `music-play` → `visualizer`. Интро `music-play` не
прерывается громкостью или микрофоном. При паузе — `music-pause` → `clock`.
При окончании музыки (или тишине дольше 1500 мс) — сразу `clock`.

## Манифест

`plugins/standard/plugin.conf` устанавливается в
`/odm/lib/quasar/plugins/standard/plugin.conf`:

```ini
format=quasar-plugin-v1
name=standard
binary=quasar_pack
idle=clock
music=visualizer
animations=music-play,music-pause,mic-on,mic-off,volume-up,volume-down,volume-ring,plasma,rainbow,starfield,pulse,rain
```

Каталог и бинарник проверяются: не symlink, владелец root или euid демона, без
групповой/всеобщей записи, бинарник — regular file с `nlink == 1` и битом `+x`.
Запускаются только режимы из манифеста.

## Как супервизор запускает плагин

```text
quasar_pack MODE --screen /dev/graphics/fb4 --ring /dev/graphics/fb5 \
  --fps 30 --brightness 64 --duration-ms 900 [--audio-fd 3] [--no-ring]
```

- `fork()` + `execve()`, один живой ребёнок, `setpgid`, `PR_SET_PDEATHSIG`.
- `RLIMIT_CORE 0`, `RLIMIT_AS 32 MiB`, `RLIMIT_NOFILE 32`.
- Все унаследованные fd кроме stdin/stdout (и fd 3 для PCM) закрываются.
- `LD_PRELOAD`/`LD_LIBRARY_PATH` вырезаются из окружения.
- stdin ребёнка: строки `button <event>` от демона (неблокирующая запись,
  при переполнении счётчик `dropped_commands`, а не рост памяти).
- stdout ребёнка: только диагностика; демон вычитывает ограниченный объём и
  отбрасывает. Никаких команд рисования по этому каналу нет.
- Визуализатор получает PCM в fd 3 из аудио-моста демона.

## Аргументы бинарника

```text
quasar_pack MODE [options]
--screen PATH        framebuffer экрана (по умолчанию /dev/graphics/fb4)
--ring PATH          framebuffer кольца (по умолчанию /dev/graphics/fb5)
--fps N              1..58, по умолчанию clock=1, остальные 30
--brightness N       0..255, по умолчанию 64
--sensitivity N      усиление визуализатора 1..10
--duration-ms N      завершиться через N мс, 0 = бесконечно
--audio-fd N         fd с сырым PCM для визуализатора
--level N            уровень для volume-ring 0..100
--no-ring            не открывать кольцо (принудительно для clock/visualizer)
--clear-on-exit      дополнительно погасить экран при штатном выходе
```

## Сборка

```sh
make                 # build/quasar_openio и build/quasar_pack
make test            # демон, скрипты, плагин, супервизор
make sanitize        # ASan/UBSan для всех трёх наборов тестов
```

Docker (статический ARM64):

```sh
docker build --platform linux/arm64 -t quasar-openio-builder .
id=$(docker create --platform linux/arm64 quasar-openio-builder)
docker cp "$id:/quasar_openio" ./quasar_openio
docker cp "$id:/plugins/standard/quasar_pack" ./quasar_pack
docker cp "$id:/plugins/standard/plugin.conf" ./plugin.conf
docker rm "$id"
```

Cross-сборка через Zig (проверена в этой среде):

```sh
python3 -m ziglang cc -target aarch64-linux-musl -static -std=c11 -O2 \
  -Iinclude -Wall -Wextra -Wpedantic -Wshadow -Wformat=2 \
  -Wconversion -Wsign-conversion -Werror \
  pack/*.c src/util.c src/framebuffer.c src/json.c -o build/aarch64/quasar_pack
```

## Установка и init

Отдельного init-сервиса для пака **нет**. `init/quasar_openio.rc` запускает только
супервизор с `--plugin-dir /odm/lib/quasar/plugins/standard`, а тот уже сам
запускает `clock` и переключает режимы.

```sh
sh scripts/install_odm.sh --preview
sh scripts/install_odm.sh --apply --confirm-odm-unprotected
setprop ctl.start quasar_openio
```

Установщик кладёт `quasar_pack` и `plugin.conf` в
`/odm/lib/quasar/plugins/standard`, ставит `quasar_openio_exec` бинарнику и
`vendor_configs_file` конфигу. Старые файлы `/odm/bin/quasar_pack` и
`/odm/etc/init/quasar_pack.rc` больше не создаются — их нужно удалить вручную.

## Тесты

`tests/test_pack.py` (реальный бинарник + подмена ioctl/mmap на host):

- часы пишут fb4 и **не трогают fb5**;
- визуализатор пишет fb4, потребляет PCM из pipe и **не трогает fb5**;
- анимация сама рисует fb5, кадры меняются, padding строки не пишется;
- после анимации и после `SIGTERM` кольцо погашено;
- все 7 обязательных анимаций существуют и используют кольцо;
- `--no-ring` не открывает устройство кольца;
- неверная геометрия fb5 → отказ от записи;
- в бинарнике нет `draw_screen`/`draw_ring`/`quasar.sock`.

`tests/test_plugin.py` (супервизор + mock-бинарник плагина):

- idle запускается с `--no-ring` и путями обоих устройств;
- анимация получает fb5 без `--no-ring` и свою длительность;
- демон не пишет в fb5 (`ring.frames == 0`, `ring.mapped == false`);
- `draw_screen`/`draw_ring` отклоняются при загруженном плагине;
- `music-play` → `visualizer`, `music-pause` → `clock`;
- авто-определение музыки по PCM и возврат к часам по тишине;
- кнопки уходят в stdin плагина и поднимают стандартные оверлеи;
- ребёнок пожинается при `SIGTERM` демона.
