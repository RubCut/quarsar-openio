# Quasar OpenIO

Скрытый foreground-сервис для Яндекс Станции Макс (`YNDX-0008`, Android 9,
ARM64, KernelSU Next). Управляет 25×16 экраном и 24-сегментным кольцом через
`mmap`, читает Алису/громкость/пульт через `poll()`, снимает микс
воспроизведения 16 кГц mono штатным Android-эффектом `Visualizer`, выводит
звук прямым `AudioTrack`. `com.yandex.io.sdk` по умолчанию **не замораживается**:
Алиса сохраняет микрофон, а `SIGSTOP` остаётся только ручной сервисной
командой.

Пакет: `quasar.rubcut.openio`. Старые наработки `quarsar-openio` не
используются.

## Архитектура

```text
domain/   чистый Kotlin/JVM, без Android API
  model/      ScreenFrame, RingFrame, QuasarMode (включая AUTO), InputEvent, AudioChunk
  render/     Font4x7, ClockRenderer, OscilloscopeRenderer, RingSpectrumRenderer,
              GifDecoder, TestPatternRenderer
  led/        LedPattern, LedPatternParser (loop, gradient, smooth, rotate)
  audio/      Pcm, PcmRingBuffer, BandPass, ScopeBand
  plugin/     PluginPack, PluginProvider, ConfigField, ProviderType
  port/       ScreenOutput, RingOutput, InputSource, AudioCapture, AudioSink
  QuasarEngine  режимы, рендер, кольцевые шаблоны, полосовой фильтр, приём PCM

app/      Android + NDK
  data/display/NativeDisplay       JNI → mmap fb4/fb5, отдельный C++ поток
  data/input/NativeInput           JNI → poll() event0/event2/event4
  data/input/InputMapper           коды клавиш → alice_pressed, volume_up, ...
  data/audio/VisualizerAudioSource  Visualizer (микс AudioFlinger) → 16 kHz mono PCM16
  data/audio/AudioTrackSink        AudioTrack 48 kHz stereo
  data/anim/AnimationStore         штатные GIF + GIF из модов, LRU-кэш
  data/plugin/PluginStore          манифесты, конфиги, безопасная установка zip
  data/plugin/PluginHost           события → скрипты/бинари, JSON-команды
  data/net/HttpServer              :8080 web UI + REST + BMP/WAV + SSE
  data/net/IpcServer               :8081 JSON/TCP
  data/net/LocalIpcServer          abstract unix socket @quasar-openio
  data/net/PcmStreamServer         :8082 raw s16le 16 kHz mono
  data/root/RootManager            su, freeze/unfreeze, autostart, доступ io.sdk к микрофону
  service/QuasarService            владелец всего runtime, авторежим
  service/PlaybackMonitor          isMusicActive + AudioPlaybackCallback
  service/BootReceiver             резервный автозапуск
  MainActivity                     первичная настройка (потом скрывается)
```

Clean Architecture соблюдается в одну сторону: `app` зависит от `domain`,
`domain` не знает об Android. JVM-тесты (`:domain:test`) проверяют кадры,
шрифт, часы, осциллограф, `.led`-парсер (включая `rotate`), полосовой фильтр,
ресемплинг и ring buffer.

## Автозапуск

```text
/odm/etc/init/quasar-openio.rc
  → /odm/quasar-openio/quasar-openio-launcher   (PIE ELF, UID 0, u:r:shell:s0)
  → ждёт SELinux Permissive (StationBreak)
  → exec /system/bin/am start-foreground-service …
  → QuasarService
```

`init` не запускает shell. `/odm` монтируется `rw` только на время установки,
затем возвращается в `ro`. `/system`, `/vendor`, `/boot`, vbmeta не
изменяются. Иконка лаунчера скрывается через
`PackageManager.setComponentEnabledSetting`; вход остаётся через веб и
`am start -n quasar.rubcut.openio/.MainActivity`.

Автозапуск можно включать и выключать во время работы:

```sh
curl http://STATION:8080/api/status            # autostart.enabled / installed
curl -X POST http://STATION:8080/api/autostart -d '{"enabled":false}'
curl -X POST http://STATION:8080/api/autostart -d '{"enabled":true}'
```

Флаг хранится в `SharedPreferences` (`autostart_enabled`, по умолчанию
`true`). При выключении сервис удаляет rc и launcher и возвращает `/odm` в
`ro`; при следующем старте сервис не восстанавливает их, пока флаг снова не
включат. Текущее состояние: **автозапуск включён** — rc и launcher
установлены в `/odm` при установке сборки `2cffee3a…`. Станция
`YNDX-0008` (`192.168.1.81`) доступна по adb; повторная проверка —
`ADB_SERIAL=192.168.1.81:5555 ./verify.sh` (см. «Артефакты»).

Первый запуск: `MainActivity` → «Настроить root, автозапуск и запустить».
Сервис сам выполняет `pm grant RECORD_AUDIO`, `chmod 0666` на fb/input,
устанавливает rc/launcher (если автозапуск включён), размораживает `io.sdk`
и скрывает иконку.

## io.sdk и микрофон Алисы

- при старте сервиса выполняется `kill -CONT` всем PID `com.yandex.io.sdk`
  (`keep_sdk_frozen=false`): без этого Алиса теряет микрофон;
- автоматической заморозки нет; `SIGSTOP` отправляется только явной
  сервисной командой (`/api/freeze` или IPC `freeze_sdk`);
- кнопка Алисы (`alice_pressed`) размораживает `io.sdk`, если он был заморожен
  вручную;
- тумблер `mic_passthrough` временно забирает у io.sdk `RECORD_AUDIO`
  (`appops … ignore`) и перезапускает его процесс, чтобы системные
  приложения могли открыть микрофон; при выключении и при остановке
  сервиса доступ возвращается (`allow`) и io.sdk стартует заново;
- при остановке сервиса/`onDestroy`: `kill -CONT` (страховка);
- никаких `pm disable`, `pm uninstall`, `am force-stop`;
- других сигналов, кроме `SIGSTOP`/`SIGCONT`, не отправляется.

## Порты и IPC

| Порт | Назначение |
| --- | --- |
| `8080/tcp` | HTTP: web UI, REST, BMP-превью, WAV/PCM снимки, SSE |
| `8081/tcp` (только loopback) | построчный JSON IPC |
| `8082/tcp` (только loopback) | сырой PCM `s16le`, 16 кГц, mono |
| `@quasar-openio` | abstract unix socket, тот же JSON IPC |

Основные HTTP команды:

```sh
curl http://STATION:8080/api/status
curl -X POST http://STATION:8080/api/mode -d '{"mode":"clock"}'          # clock|oscilloscope|animation|test|off|auto
curl -X POST http://STATION:8080/api/render -d '{"ring_color":"#FFB300","ring_enabled":true,"scope_fps":20,"scope_sensitivity":2.5,"scope_smooth":1500,"scope_band":"mid","ring_origin":12}'
curl -X POST http://STATION:8080/api/ring/pattern -d '{"provider":"quasar.yandex-station2/ring.station.progress","loop":true}'
curl -X POST http://STATION:8080/api/ring/pattern -d '{}'                # остановить шаблон
curl http://STATION:8080/api/mods                                        # моды и предметы
curl -X POST http://STATION:8080/api/mods/install --data-binary @mod.zip
curl -X POST http://STATION:8080/api/mods/remove -d '{"id":"my.mod"}'
curl -X POST http://STATION:8080/api/mods/config -d '{"provider":"quasar.oscilloscope/visualizer.scope.wave","values":{"band":"bass","fps":22}}'
curl -X POST http://STATION:8080/api/mods/activate -d '{"item":"quasar.yandex-station2/ring.station.progress","loop":true}'
curl -X POST http://STATION:8080/api/autostart -d '{"enabled":false}'
curl -X POST http://STATION:8080/api/freeze -d '{"action":"freeze"}'
curl -X POST --data-binary @frame.rgb http://STATION:8080/api/frame   # 1200 байт RGB888
curl http://STATION:8080/api/mic.wav?ms=2000 -o mic.wav
curl http://STATION:8080/api/frame.bmp -o frame.bmp
```

JSON IPC (`nc 127.0.0.1 8081` на станции или через `adb forward`):

```json
{"cmd":"status"}
{"cmd":"draw_screen","data":"<base64 RGB888 1200 байт>"}
{"cmd":"get_mic_pcm","samples":1600}
{"cmd":"resume_sdk"}
{"cmd":"freeze_sdk"}
{"cmd":"set_mode","mode":"auto"}
{"cmd":"set_render","ring_color":"#4ADE80","scope_fps":8,"scope_band":"bass","ring_origin":3}
{"cmd":"set_autostart","enabled":false}
{"cmd":"play_tone","frequency":440,"ms":400}
```

События приходят в тот же поток: `{"event":"alice_pressed",...}`,
`{"event":"volume_up",...}`, `{"event":"remote_ok",...}`,
`{"event":"auto_mode",...}`, `{"event":"ring_pattern",...}`,
`{"event":"plugin_installed",...}` и т.д. HTTP-клиенты получают их через SSE
`/api/events`.

## Аудио

- Вход: штатный Android-эффект `Visualizer` (сессия 0). В прошивке есть
  `/vendor/lib/soundfx/libvisualizer.so`, объявленный в
  `/vendor/etc/audio_effects.conf`, поэтому эффект снимает **готовый микс
  AudioFlinger**: музыку, видео (в том числе SmartTube) и голос Алисы.
  Ни ALSA, ни `AudioRecord`, ни микрофон не открываются — воспроизведение
  и Алиса продолжают работать как обычно.
- Слушатель подписывается на `Visualizer.getMaxCaptureRate()` (на этой
  прошивке 20 000 мГц = 20 колбэков/с) с окном 1024 сэмпла. Волна приходит
  как unsigned 8-bit с центром 128: код переводит её в PCM16 и децимирует
  микс 44,1 кГц → 16 кГц. Практический потолок визуализатора — ~20 кадров/с,
  он виден в `/api/status` как `render.scope_fps_actual`.
- Захват открывается только в режиме `oscilloscope` (в авторежиме — когда
  играет медиа) и закрывается при переходе в часы/анимацию/тест/off, а также
  перед `unfreezeSdk()`, чтобы эффект не висел поверх сессии Алисы.
- `prepareRuntime()` выдаёт `RECORD_AUDIO` и приложению (клиент эффекта), и
  `com.yandex.io.sdk`, чтобы Алиса сохранила доступ к микрофону.
- Если эффект отвалился (AudioFlinger пересоздал выходной поток), сервис
  повторяет попытку с backoff 15→120 с и показывает ошибку в `/api/status`
  (`audio.capture`, `audio.input_error`).
- Почему не прямой ALSA (`tinycap2`): на этой прошивке TDM-A общий для
  capture и playback. Жёсткое завершение оставляет MCLK выключенным,
  захват падает с `Unable to open PCM device ()`, а DMA воспроизведения
  клинит до перезагрузки. Такой путь удалён из кода.
- Почему не `AudioRecord`: открытие/закрытие идёт через вендорный
  `AudioPolicyService`, а сессия конкурирует с Алисой за микрофон.
  Визуализатору микрофон не нужен вовсе.
- Выход: `AudioTrack`, 48 кГц, stereo, `USAGE_MEDIA`. Это осознанный
  fallback: `appsrc name=stream-src` находится внутри
  `libquasar_daemons.so` процесса `io.sdk`, внешнего протокола подачи PCM в
  него нет.
- `AudioTrack` не трогает системную громкость, часовой пояс и настройки
  `io.sdk`.

## Дисплей

- `fb4`: 25×16, RGB888, stride 75, offsets R16/G8/B0, `smem_len` 4096.
- `fb5`: 24×1, RGB888, stride 72.
- Только `mmap` (`PROT_READ|PROT_WRITE`, `MAP_SHARED`), без `write()`.
- После записи кадра обязателен commit: `ioctl(fd, FBIOPAN_DISPLAY, &var)`.
  Драйверы `quasar_led_screen` и `quasar_led_ring` отправляют изображение
  на панель/кольцо именно из `fb_pan_display` (`qls_fb_sync`/`fb_sync` →
  `sa_show_pic` / `led_set_brightness`). Без этого ioctl кадр остаётся
  только в памяти и панель показывает последний отправленный кадр.
- Kotlin публикует последний кадр, C++ поток рисует изменившееся и
  вызывает `FBIOPAN_DISPLAY` для соответствующего fb.

## Экран, кольцо и визуализатор

- **Часы**: только белые цифры `HH:MM` на чёрном фоне. Нижней полосы,
  прогресса, сетки и других линий нет; двоеточие мигает раз в секунду.
  Цифры рисуются штатными атласами io.sdk
  (`frontal_animation/fonts/clock_{large,middle,small}.gif`): 5×9, 4×7 и
  3×5. Крупный размер занимает панель от края до края и пиксель в пиксель
  совпадает с эталоном `25x16/25x16_Clock.gif`.
- **Кольцо**: 24 светодиода. Физический индекс `0` — задняя/нижняя
  точка кольца, индексы растут **против часовой стрелки**, поэтому
  передний светодиод — `12`. Часы идут **по часовой стрелке**. Параметр
  `ring_origin` (0–23) привязывает «передний» светодиод к физическому;
  по умолчанию — `12`. Сдвиг применяется и к шаблонам, и к
  сгенерированному кольцу. Кольцо можно полностью выключить
  (`ring_enabled=false`) или задать любой цвет.
- **Шаблоны кольца**: `.led`-файлы Яндекс Станции (118 штук в моде).
  Поддерживаются `loop`, `gammacorrection`, `smooth N`, `gradient`,
  обычные кадры `RRGGBB … <ms>`, свободный формат с brightness и директива
  `rotate` (вращение кадра на один LED за длительность кадра — так работает
  штатный `progress.led`). Активный шаблон приоритетнее VU-уровня и
  часового кольца; статичный кадр, присланный скриптом, приоритетнее
  шаблона. Удаление мода снимает и шаблон, и статичный кадр.
- **Визуализатор**: один визуализатор — «Волна». Одна белая линия на
  чёрном фоне, без сетки и центральной линии, все пиксели линии одной
  яркости. Сигнал берётся из микса воспроизведения: тихий материал
  подтягивается автоматическим усилением (пик окна к ~79 % шкалы, не более
  ×96), поверх него работает пользовательская чувствительность 0.25–8.
  Волна обновляется по таймеру 10 мс и только при новом аудиочанке:
  `scope_fps` задаёт верхнюю границу 1–50, фактическая частота видна как
  `scope_fps_actual` (на этой прошивке ~20 из-за `Visualizer`).
- **Плавность** (`scope_smooth`, 0–5000 мс, по умолчанию 1500): когда
  воспроизведение останавливается, последняя волна не сменяется мгновенно
  прямой — она плавно затухает к центру за заданное время. `0` возвращает
  мгновенную прямую. PCM старше 180 мс считается тишиной, поэтому старый
  буфер не «замораживает» картинку. Кольцо в режиме визуализатора — это
  VU-уровень, спектра на кольце больше нет.
- **Диапазон частот** (`scope_band`) — это одновременно полосовой фильтр
  (RBJ biquad) и развёртка окна: `full` 20 Гц–8 кГц, `bass` 20–200 Гц,
  `low_mid` 200–800 Гц, `mid` 0.8–3 кГц, `high` 3–8 кГц. Низкие частоты
  показываются медленнее и крупнее, высокие — быстрее и мельче.
- **Анимации**: все 105 штатных GIF из `frontal_animation/25x16` вшиты в
  `assets/anim` и сгруппированы по папкам (`media`, `oknix`, `calls`, …).
  Декодер написан на чистом Kotlin и сохраняет исходные оттенки: клипы
  содержат до 122 уровней серого, а не два. Любой клип можно проиграть
  поверх текущего режима или назначить idle-анимацией; цвет анимации
  задаётся отдельно.
- Настройки (`ring_color`, `ring_enabled`, `scope_fps`,
  `scope_sensitivity`, `scope_smooth`, `scope_band`, `ring_origin`,
  `clock_size`, `idle_animation`, `animation_color`, `auto_enabled`)
  сохраняются в `SharedPreferences` (`quasar_settings`) и переживают
  перезапуск сервиса. В API остались `ring_spectrum=false` и
  `scope_style=wave` для старых клиентов.

## Авторежим

`POST /api/mode {"mode":"auto"}` включает сервисный флаг `auto_enabled`;
`mode` в статусе становится `auto`, а фактический рендер — в
`mode_effective` (`clock` или `oscilloscope`). Логика:

- ничего не играет → **часы**;
- медиа реально играет → **визуализатор** (и захват открывается);
- воспроизведение определяется только
  `PlaybackMonitor.currentState() == MediaState.PLAYING`
  (`AudioManager.isMusicActive()` + `AudioPlaybackCallback`); загруженный
  или поставленный на паузу плеер визуализатор не включает;
- имена активных пакетов берутся из callback (видны в `auto.packages` и
  только для информации);
- сервис никогда не останавливает и не ставит на паузу чужие приложения;
  решение принимается только по факту воспроизведения.

Чтобы не дёргать картинку на коротких звуках, смена режима применяется с
задержкой: 1.5 с на старт музыки и 12 с на остановку (переживает паузы
между треками и рекламу). `auto.playing` в статусе — текущее состояние
`PLAYING`.

Выбор любого другого режима снимает `auto_enabled`.

## Моды и предметы

Встроенные моды лежат в `assets/plugins/<id>/`, пользовательские — в
`filesDir/plugins/<id>/`. Мод — это папка с `manifest.json` и
необязательными `scripts/`, `bins/`, `led/`, `anim/`.

Манифест описывает **предметы** (`items`): у каждого есть `kind`
(`screen` / `ring` / `script` / `service`) и, для экранных, `slot`
(`clock` / `visualizer` / `animation`). Старый формат `providers` с
полем `type` продолжает работать: тип конвертируется в kind, а слот
выводится из типа или `builtin`.

Архив для установки содержит верхнюю папку `plugin/`:

```text
plugin/manifest.json
plugin/led/hello.led
plugin/scripts/on_alice.sh
```

Ограничения: 64 МБ архив, 64 МБ распакованного, 5000 файлов, пути
проверяются на `..`; id — только `a-z0-9._-`. Скрипты и бинари получают
бит execute после установки.

Пример манифеста:

```json
{
  "id": "com.example.my-mod",
  "name": "Мой мод",
  "version": "1.0.0",
  "author": "you",
  "items": [
    {
      "id": "ring.hello",
      "kind": "ring",
      "name": "Приветствие",
      "group": "Мои шаблоны",
      "resource": "led/hello.led"
    },
    {
      "id": "clock.custom",
      "kind": "screen",
      "slot": "clock",
      "name": "Часы",
      "builtin": "clock",
      "config": [
        {"key": "clock_size", "label": "Размер", "kind": "select", "default": "large",
         "options": [{"value": "large", "label": "Крупные"},
                     {"value": "middle", "label": "Средние"},
                     {"value": "small", "label": "Мелкие"}]}
      ]
    },
    {
      "id": "events.alice",
      "kind": "script",
      "name": "Кнопка Алисы",
      "entry": "scripts/on_alice.sh",
      "events": ["alice_pressed"]
    }
  ]
}
```

Виды предметов:

| `kind` | `slot` | Что делает при активации |
| --- | --- | --- |
| `screen` | `clock` | выбирается в слот часов |
| `screen` | `visualizer` | выбирается в слот визуализатора (`builtin`: `oscilloscope`) |
| `screen` | `animation` | назначается idle-анимацией (`resource`: `builtin:anim/…` или файл мода) |
| `ring` | — | включает `.led`-шаблон из `resource` |
| `script` | — | запускает `entry` на события из `events` (или разово через `/api/mods/activate`) |
| `service` | — | фоновый процесс под вотчдогом OpenIO (`entry`, `interpreter`, `restart`) |

Эквиваленты legacy-типов: `clock` → `screen/clock`, `visualizer` →
`screen/visualizer`, `animation` → `screen/animation`, `ring` → `ring`,
`script` → `script`, `service` → `service`.

Конфиг-поля (`config`) поддерживают `kind`: `select`, `range`, `number`,
`boolean`, `color`, `text`, `min`/`max`/`step`, `unit`, `help`, `options`.
Значения сохраняются на предмет и применяются к встроенным виджетам по
ключам `clock_size`, `band`, `fps`, `sensitivity`, `smooth`,
`color`, `animation_color`.

Протокол скрипта:

- сервис передаёт JSON события на stdin;
- окружение: `QUASAR_API` (`http://127.0.0.1:8080`), `QUASAR_PACK`,
  `QUASAR_PROVIDER`, `QUASAR_PACK_DIR`, `QUASAR_CONFIG` (JSON конфига);
- stdout — по одной JSON-команде на строку:

```json
{"ring":"#3366ff"}
{"ring":["#000000", "… 24 цвета"]}
{"pattern":"led/alice.led","loop":true}
{"animation":"builtin:anim/media/25x16_Pause.gif"}
{"mode":"oscilloscope"}
{"notify":"текст"}
```

- таймаут 5 с, вывод ограничен 64 КБ, пул из трёх потоков; скрипт никогда
  не выполняется на потоке аудио/ввода/HTTP;
- пустой список `events` означает «не подписан», а не «все события» —
  иначе обработчик уведомлений зациклился бы сам на себе.

Встроенные моды этой сборки:

| Мод | Что внутри |
| --- | --- |
| `quasar.yandex-station2` | 118 шаблонов кольца (39 активных, 39 Quark, 40 Nebula), 105 GIF, часы, визуализатор, пример скрипта — 225 предметов |
| `quasar.oscilloscope` | единственный визуализатор «Волна» с конфигами диапазона, FPS и чувствительности |

Веб-интерфейс (карточка **«Моды и предметы»** в разделе «Общие») показывает
моды и предметы с их kind/slot, даёт установить zip, удалить пользовательский
мод, включать и выключать моды и предметы, отредактировать параметры рядом с
каждым предметом, запустить сервис/скрипт и активировать предмет своего вида
(экранный — в свой слот, кольцо — как шаблон).

## Веб-панель и новые подсистемы

Веб-интерфейс переписан в стиле Material Design 3 (оффлайн, без внешних
CDN/шрифтов): нижняя навигация с 4 разделами — **Часы**, **Визуализаторы**,
**Анимации**, **Общие**. Основной сценарий максимально упрощён: в каждом
разделе видны только переключатель, крупный предпросмотр и одно главное
действие, а выбор провайдера, конфиги, шаблоны кольца, кнопки, система и
журнал спрятаны в раскрывающиеся блоки **«Дополнительно»** (`<details>`).
На телефонах (`@media (max-width: 640px)`) уменьшены отступы и превью,
увеличены зоны нажатия, а нижняя навигация остаётся всегда под рукой.
Над разделами всегда виден живой предпросмотр: экран 25×16
(`/api/frame.bmp`) и кольцо 24 LED (canvas, `/api/ring`). Тема — фиолетовая
палитра MD3, тёмная/светлая по `prefers-color-scheme`; компоненты (switch,
слайдеры, сегментные кнопки, диалоги `mdConfirm`, snackbar, bottom nav)
отрисованы вручную на CSS/JS.

Новые подсистемы (все настройки живут в prefs `quasar_settings`):

- **Мастер-выключатель** (`master_enabled`, default true): «off» гасит всю
  панель (режим `off`, захват закрыт), запоминает прежний режим в
  `master_prev_mode`; «on» возвращает режим или авторежим. Пока мастер
  выключен, `setMode` принимает только `off`.
- **Экран** (`screen_enabled`): гасит только экран 25×16, кольцо продолжает
  работать (`QuasarEngine.screenEnabled` гейтит publish и drawCustomFrame).
- **Микрофон** (`mic_share`, default true): при выключении наш захват не
  открывается вовсе (io.sdk владеет микрофоном); при активации Алисы захват
  приостанавливается на 12 с (`ALICE_CAPTURE_PAUSE_MS`).
- **Проброс микрофона** (`mic_passthrough`, default false): наш захват
  останавливается, `appops set com.yandex.io.sdk RECORD_AUDIO ignore`
  забирает разрешение у io.sdk, а `kill -9` перезапускает его, чтобы
  закрылась уже открытая сессия записи — после этого `AudioRecord` могут
  открыть системные приложения. Watchdog повторяет запрет, если система
  сбросила app-op; при выключении и при остановке сервиса возвращается
  `allow` и io.sdk снова стартует с микрофоном.
- **Автоанимации медиа** (`media_anim_enabled/play/pause/stop`, выключены по
  умолчанию): при старте/паузе/остановке воспроизведения один раз
  проигрывается оверлей штатного io — по умолчанию
  `media/25x16_Resume.gif` для play и `media/25x16_Pause.gif` для pause,
  stop пустой; клипы настраиваются из каталога `/api/animations`
  (кроме тестовых). Определение состояния — `PlaybackMonitor.currentState()`
  (config-и + `isMusicActive`), с задержкой 0.8/0.8/3 с для устойчивости.
- **Кольцо → громкость** (`ring_volume_enabled` default true,
  `ring_volume_step` 1–5, `ring_volume_anim` default true): вращение
  энкодера меняет `STREAM_MUSIC` (фолбэк — root `input keyevent 24/25`);
  фидбек как в штатном io — клип `rcu_volume/volume_rotation|volume_pulse/
  <up|down>/<0..10>.gif` на экране и дуга LED на кольце, автоочистка через
  1.6 с после последнего щелчка.
- **Переназначение кнопок** (`input_bindings`): JSON-привязки ключей
  (`ALICE`, `VOLUME_UP/DOWN`, `MUTE`, `REMOTE_*`, `HEADPHONES`) к действиям:
  `none, volume_up/down, play_pause, next, previous, stop_media, alice,
  mute, toggle_screen, toggle_ring, clock, visualizer, animation`,
  `script:<itemKey>` (скрипт мода), `anim:<clipId>` (оверлей). Дефолты:
  Алиса→alice, кольцо→громкость, mute→журнал, пульт play/next/prev→медиа.
- **Алиса**: при нажатии кнопки Алисы (или POST `/api/alice`) сервис
  размораживает `io.sdk`, если пользователь его замораживал, и только тогда
  шлёт `input keyevent 219` (VOICE_ASSIST) — без двойного срабатывания,
  когда io.sdk получает кнопку нативно.

Новые эндпоинты:

| Метод/путь | Что делает |
| --- | --- |
| `GET /api/general` | `{ok, master, screen, mic_share, mic_passthrough, ring_volume{enabled,step,anim}, media_anim{enabled,play,pause,stop}, input, alice_available}` |
| `POST /api/general` | подмножество `{master, screen, mic_share, mic_passthrough, ring_volume_enabled, ring_volume_step, ring_volume_anim}` |
| `POST /api/media-anim` | подмножество `{enabled, play, pause, stop}` (`""` = нет клипа) |
| `GET /api/remap` | `{ok, bindings[{key,title,device,action}], actions[{id,title}]}` (действия дополнены `script:*`/`anim:*`) |
| `POST /api/remap` | `{key, action}` или `{reset:true}` |
| `POST /api/alice` | активация Алисы (разморозка + VOICE_ASSIST) |

В `/api/status` добавлены блоки `general`, `media_anim` и массив `remap`;
SSE публикует события `master`, `general`, `screen_enabled`, `mic_share`,
`mic_passthrough`, `ring_volume`, `media_anim`, `media_anim_config`,
`volume_changed`, `remap`, `alice_activated`.

Android-приложение тоже переведено на Material Design 3: тема
`Theme.AppCompat.DayNight.NoActionBar` (androidx.appcompat 1.7.0 +
core-ktx 1.13.1, оффлайн-констрейнты lifecycle 2.8.6/annotation 1.8.1/coroutines
1.8.1 — все версии есть в локальном кэше Gradle), карточки, кнопки
filled/tonal/outlined с рябью, светлая/тёмная палитра. Новая иконка —
adaptive (`mipmap-anydpi-v26`, фон+передний план+monochrome для themed icons
Android 13+) и legacy PNG во всех плотностях: LED-кольцо из 24 точек вокруг
dot-matrix панели. Скрытие иконки лаунчера сохранено (activity-alias).

## Сборка

```sh
./build.sh          # launcher + domain tests + debug APK + dist/SHA256SUMS
./install.sh        # adb install, setup, status
./verify.sh         # проверка пакета, сервиса, rc, /odm, HTTP, паков
```

Требования: JDK 17, Android SDK `platforms/android-34`, Linux build-tools
`34.0.0`, NDK r26d (`26.3.11579264`), CMake/Ninja. Пути по умолчанию
соответствуют этой машине; переопределяются через `ANDROID_SDK_ROOT`,
`ANDROID_NDK_ROOT`, `JAVA_HOME`, `ADB_SERIAL`.

## Эмулятор панели (ПК)

Модуль `emulator/` — Swing-приложение, которое хостит настоящий
`QuasarEngine` (тот же код, что на станции) с синтетическим источником
звука и рисует экран 25×16 и кольцо 24 LED. Станция не нужна — она может
быть выключена; Алиса и голосовые функции не используются вовсе.

```sh
./emulator.sh                        # GUI
./emulator.sh --selftest TONE_440    # headless ASCII-проверка
```

- Источники звука: тишина, тон 440/1000 Гц, шум и симуляция музыки
  (кик + бас + хэты + аккорд), чтобы волна повторяла материал как на
  станции.
- Контролы: режим, источник, уровень и чувствительность, `clock_size`,
  idle-GIF (как на панели). Визуализатор один — «Волна»; спектра на кольце
  и выбора стилей больше нет. Кольцо эмулятора показывает физическую
  раскладку: LED 0 снизу, LED 12 спереди.
- В GUI можно переключить источник экрана/кольца на «живой вид» станции
  через HTTP (`adb forward tcp:18080 tcp:8080`, URL — первый аргумент,
  по умолчанию `http://127.0.0.1:18080`); это опция, по умолчанию
  рисуется локальный движок.
- Сборка модуля: `./gradlew :emulator:installDist` (при недоступной сети
  добавить `--offline` — зависимости уже в кэше).
- Selftest прогоняет 125 кадров движка и печатает экран ASCII
  (5 уровней яркости): волна для выбранного звука и часы `21:47` крупным
  шрифтом.

## Ограничения и безопасность

- `mmap` fb/input требует root: при старте выполняется временный
  `chmod 0666` на устройства. Постоянных правил SELinux нет.
- Автозапуск пишет только в `/odm/quasar-openio` и `/odm/etc/init`.
- `unfreezeSdk`/остановка сервиса всегда возвращают `io.sdk` в работу.
- Перезагрузка выполняется только вручную; приложение не вызывает
  `reboot`, не меняет время и `persist.sys.timezone`.
- В циклах нет `system()`: root-команды идут через `Runtime.exec`/`su -c`,
  в NDK — только `fork/exec` (launcher использует `execl`).
- Установка мода не выполняет код из архива во время распаковки: скрипты
  запускаются только по событиям, с таймаутом и ограниченным выводом.

## Известные ограничения

- Подача PCM в `appsrc` внутри `io.sdk` не реализована: нет внешнего
  протокола; используется прямой `AudioTrack`.
- `Visualizer` отдаёт не более 20 обновлений в секунду
  (`getMaxCaptureRate()` = 20 000 мГц), поэтому `scope_fps` выше 20 не
  добавляет кадров: таймер рендера просто не находит нового `pcmSeq`.
- `AudioPlaybackConfiguration.getPlayerState()`/`getClientUid()` скрыты в
  SDK; на этой прошивке рефлексия заблокирована. Поэтому имена
  media-пакетов в `auto.packages` могут быть пустыми. Сам факт
  воспроизведения от этого не зависит: авторежим смотрит на
  `MediaState.PLAYING`, а не на список пакетов.
- `dumpsys media.audio_flinger` **нельзя вызывать** на этой прошивке: он
  роняет audio HAL (`HalDeathHandler`), после чего `audioserver`
  респавнится в `u:r:init:s0`, `media.audio_policy`/`media.audio_flinger`
  не регистрируются, и звук не восстанавливается без перезагрузки. Проверка
  в `verify.sh` удалена намеренно; `dumpsys audio` безопасен.
- Если audio HAL уже упал, микрофон и визуализатор мертвы до перезагрузки:
  рестарт `audioserver` вручную не помогает (SELinux не даёт `runcon` в
  `u:r:audioserver:s0`, `ctl.stop audioserver` запрещён).
- Filesystem-сокет `/data/local/tmp/quasar.sock` в Android 9 недоступен
  через публичный `LocalServerSocket`; используется abstract
  `@quasar-openio`, а основной IPC — TCP `127.0.0.1:8081`.

## Артефакты и проверка

Актуальная сборка 2026-11-19 — плавное затухание волны
(`scope_smooth`), один визуализатор «Волна» без штатных спектр-пресетов,
честный авторежим по `MediaState.PLAYING`, проброс микрофона системным
приложениям (`mic_passthrough`), кольцо с передним LED `12` и часами по
часовой стрелке, максимально упрощённый UI с телефонным видом. Сборка
установлена на станцию и проверена:

| Файл | SHA-256 |
| --- | --- |
| `dist/quasar-openio-debug.apk` | `2cffee3ac4487c03e89b8d22158f61d2ec56ef37a37c3535732bde9eb0eb8039` |
| `dist/quasar-openio-launcher` | `f35b58ee93007bdfc3733bfe24b014a5964fdb7bfd96bf8937c7bed9f6c63554` |
| `/odm/etc/init/quasar-openio.rc` | установлен: автозапуск включён |

На `YNDX-0008` (`192.168.1.81:5555`) сборка проверена 2026-11-19:
`./verify.sh` проходит полностью; `ring_origin=12`,
`ring_direction=clockwise`, `ring_spectrum=false`, `scope_style=wave`,
`scope_smooth=1500`, `mic_passthrough=false`; `io.sdk` в состоянии `RUN`
и не заморожен, `RECORD_AUDIO` выдан и приложению, и `io.sdk`; волна
берётся из микса воспроизведения (`/api/mic.pcm` отдаёт ненулевой PCM),
`scope_fps_actual` = 20–25; `fb4` меняется во времени.

Живые проверки этой сборки:

- **Плавность**: при `scope_smooth=2000` после тона волна затухает
  постепенно (размах 8 → 6 → 4 → 2 → 0 строк за ~1.2 с), а не мгновенно.
- **Авторежим**: в тишине `auto.playing=false`, `mode_effective=clock`;
  во время реального воспроизведения `auto.playing=true` и через ~1.5 с
  включается `oscilloscope`; после остановки — ещё 12 с визуализатора и
  возврат к часам.
- **Проброс микрофона**: включение даёт `RECORD_AUDIO: ignore` у
  `com.yandex.io.sdk`, перезапускает его процесс, закрывает наш захват и
  показывает `mic_passthrough=true`; выключение возвращает `allow`,
  перезапускает io.sdk и снова открывает захват визуализатора.
- **Кольцо**: при `ring_origin=12` индекс растёт против часовой стрелки,
  поэтому часы идут по часовой: на 15-й секунде горит LED `6`, на
  45-й — `18`.

Моды и авторежим проверены на устройстве: `/api/mods` отдаёт два
встроенных мода и пользовательские предметы с kind/slot; установка zip
`plugin/manifest.json` + `led/` + `scripts/`, активация предмета по kind,
сохранение конфига и удаление мода работают; скрипт по `alice_pressed`
перекрасил кольцо через `{"ring":"#00ff00"}`; `scope_band=mid`
сохраняется. Активный `.led`-шаблон перекрывает VU-кольцо в режиме
визуализатора; при удалении мода снимаются и шаблон, и статичный кадр.

Порт оригинальных ассетов проверен отдельно: часы `04:58` совпадают с
`25x16_Clock.gif` пиксель в пиксель (golden-тест), все 105 GIF декодируются
в те же RGB-кадры, что и эталонный декодер (включая 122 оттенка серого),
idle-анимация и оверлей работают на устройстве, три размера часов дают
разные кадры, каталог `/api/animations` отдаёт 105 клипов в 13 группах.

## Проверка на станции

```sh
adb -s 192.168.1.81:5555 forward tcp:18080 tcp:8080
curl http://127.0.0.1:18080/api/status
curl http://127.0.0.1:18080/api/plugins
adb shell 'su -c "for p in \$(pidof com.yandex.io.sdk); do awk \"{print \\\$3}\" /proc/\$p/stat; done"'
```

Ожидается `S`/`R` у PID `io.sdk` (не `T`), `S` у `quasar.rubcut.openio`,
`"display":{"started":true}`, `"root":true`, `sdk.frozen:false`,
`keep_sdk_frozen:false`, `ring_origin:12`, `ring_direction:"clockwise"`,
`ring_spectrum:false`, `scope_style:"wave"`, `scope_smooth:1500`,
`mic_passthrough:false`.