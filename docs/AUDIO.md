# Аудио-мост и `appsrc name=stream-src`

## Что реализовано

`quasar_openio` принимает сырой PCM по TCP `127.0.0.1:19080` и отдаёт его по HTTP
`127.0.0.1:19081`. Оба конца требуют bearer token из приватного файла
`/data/local/tmp/quasar-openio/audio.token` (`0600`). Loopback сам по себе не
авторизует локальное приложение. Формат фиксирован: `S16LE`, 48000 Hz, stereo. Буфер ограничен
262144 байтами, переполнение вытесняет старые кадры, второй producer и второй
streaming-consumer отклоняются. Всё это находится в одном `poll()`-цикле демона,
без потоков и неограниченных очередей. `fork()` используется только супервизором
при смене режима плагина — никогда на кадр или на аудио-чанк. PCM дополнительно
зеркалится в pipe визуализатора (`--audio-fd 3`) короткими неблокирующими
записями не больше 4096 байт; при переполнении pipe чанк отбрасывается.

Таймауты: PCM handshake — абсолютные 5 с с момента accept; аутентифицированный
producer без PCM — 30 с; HTTP-запрос — 5 с; streaming-consumer без реального
send-прогресса — 15 с (`QIO_HTTP_STREAM_IDLE_MS`, compile-time override для
тестов). Неверный токен не принимает PCM и не влияет на auto-music.

Это транспортный слой с обязательной локальной аутентификацией. Он **не**
утверждает, что штатный плеер уже подключён к HTTP, и **не** внедряется в чужой
GStreamer pipeline.

## Протокол аутентификации

Демон генерирует 32 случайных байта из `/dev/urandom`, кодирует в 64 hex и
создаёт token-файл через `O_EXCL` в каталоге `0700`. При штатном завершении файл
удаляется; после `SIGKILL` следующий запуск под instance lock безопасно заменяет
stale-файл. Сравнение токена constant-time по фиксированной длине.

PCM producer обязан первым отправить:

```text
AUTH <64 lowercase hex>\n
```

Только после этого принимаются кадры S16LE. HTTP-клиент обязан передать заголовок
на каждый запрос:

```text
Authorization: Bearer <64 lowercase hex>
```

Примеры читают токен из файла и не помещают его в argv:

```sh
python3 examples/pcm_send.py --token-file /data/local/tmp/quasar-openio/audio.token audio.raw
python3 examples/audio_http.py --token-file /data/local/tmp/quasar-openio/audio.token --output out.wav
```

`tcpclientsrc`/`souphttpsrc` из командной строки не должны использоваться без
адаптера, который добавляет AUTH/Authorization. Передача токена через аргументы
процесса или URL не рекомендуется.

## Почему нельзя просто вызвать чужой `appsrc` из C

`appsrc name=stream-src` — это GObject внутри адресного пространства процесса
`com.yandex.io.sdk`. Имя элемента имеет смысл только внутри конкретного
`GstBin`/`GstPipeline`. Внешний процесс не может сделать:

```c
/* Так нельзя: pipeline находится в другом процессе. */
GstElement *src = gst_bin_get_by_name(GST_BIN(pipeline), "stream-src");
```

если у него нет указателя на этот pipeline, полученного внутри того же процесса.
Универсального Binder/Unix API «передать буфер в appsrc по имени» в Android нет.
Поэтому прямовой C-вызов из отдельного демона невозможен без внутрипроцессного
компонента или документированного IPC самого `io.sdk`.

## Вариант A: внутрипроцессный адаптер к существующему `stream-src`

Нужен компонент, загруженный в процесс `io.sdk` и имеющий доступ к GObject:

1. Найти pipeline и элемент `stream-src` внутри процесса.
2. Проверить `GST_IS_APP_SRC(src)` и текущие caps/состояние pipeline.
3. Подключить локальный Unix socket или общий буфер к демону.
4. В отдельном потоке/GLib source читать PCM ограниченными чанками.
5. Выставлять caps `audio/x-raw,format=S16LE,rate=48000,channels=2,layout=interleaved`.
6. Передавать буферы через `gst_app_src_push_buffer()` или сигналы
   `need-data`/`enough-data`, не превышая `max-bytes`/очередь.
7. Корректно обрабатывать EOS, pause/resume, потерю соединения и остановку SDK.

Минимальный фрагмент **внутри процесса**, где `pipeline` уже известен:

```c
GstElement *src = gst_bin_get_by_name(GST_BIN(pipeline), "stream-src");
if (src == NULL || !GST_IS_APP_SRC(src)) {
    /* fail closed */
}
GstCaps *caps = gst_caps_new_simple("audio/x-raw",
    "format", G_TYPE_STRING, "S16LE",
    "rate", G_TYPE_INT, 48000,
    "channels", G_TYPE_INT, 2,
    "layout", G_TYPE_STRING, "interleaved",
    NULL);
gst_app_src_set_caps(GST_APP_SRC(src), caps);
gst_caps_unref(caps);
/* Далее: bounded queue -> GstBuffer -> gst_app_src_push_buffer(). */
gst_object_unref(src);
```

Способ загрузки адаптера зависит от устройства и версии APK: существующий
plugin/JNI-механизм, Frida/ptrace на root, либо модификация загружаемой native
библиотеки. В этом репозитории такой hook не реализован, потому что для него нужны
точные APK/native offsets, ABI и SELinux-права. Нельзя угадывать адрес pipeline или
считать `SIGSTOP` средством доступа к памяти процесса.

## Вариант B: отдельный GStreamer pipeline, не внутри `io.sdk`

Если цель — воспроизвести внешний PCM через открытый стек, а не обязательно через
штатный `stream-src`, можно запустить отдельный pipeline. Он не внедряется в
`io.sdk` и может конфликтовать за audio focus/ALSA device:

Прямой `tcpclientsrc` больше не работает: он не отправляет `AUTH`. Нужен
локальный аутентифицированный адаптер, который читает токен из 0600-файла,
отправляет handshake и только затем проксирует PCM в pipeline.

Или через HTTP:

`souphttpsrc` должен получить `Authorization: Bearer ...` через программно
заданные extra-headers внутри доверенного процесса, а не через токен в URL/argv.
После авторизации поток остаётся `audio.wav`/`audio.pcm`; совместимость
`wavparse ignore-length=1` нужно проверять на устройстве.

На Android sink может быть `openslessink`/`aaudiosink` в зависимости от сборки
GStreamer. Streaming WAV с неизвестной длиной поддерживается не всеми
элементами; `ignore-length` и поведение при EOF нужно проверить. Этот вариант
обходит монополию `io.sdk` только если штатный pipeline не держит нужный ALSA
subdevice или корректно уступает audio focus.

## Вариант C: штатный плеер забирает loopback HTTP

Демон отдаёт `http://127.0.0.1:19081/audio.wav` и `/audio.pcm` только с bearer
token. Чтобы штатный
плеер использовал этот URL, нужен подтверждённый способ передать URI плееру:
Intent, Binder-сервис, локальный API или конфигурация. В рамках этого репозитория
такой способ не подтверждён.

Проверять нужно на устройстве:

- принимает ли плеер `http://127.0.0.1` и MIME `audio/wav`;
- поддерживает ли chunked/close-delimited WAV с длиной `0xffffffff`;
- какой сервис/Intent меняет источник без перезапуска всего пакета;
- не блокирует ли SELinux loopback-соединение и доступ к token-файлу;
- что происходит при pause/resume SDK и при разрыве producer.

Нельзя рассылать `am start` на каждое аудио-событие: это создаёт Java-процессы и
повторяет проблему OOM. Команда запуска источника должна быть редкой,
идемпотентной и выполняться вне горячего пути, если вообще подтверждена.

## Рекомендованный порядок

1. Использовать реализованный PCM→HTTP транспорт и проверить его внешним
   потребителем.
2. На устройстве найти документированный/наблюдаемый способ передать URI штатному
   плееру. Если его нет — не маскировать это под «готовое внедрение».
3. Если требуется именно `stream-src`, делать внутрипроцессный адаптер с bounded
   queue, проверкой caps и корректным teardown.
4. Если допустим отдельный playback pipeline, использовать вариант B и проверить
   audio focus/ALSA ownership.
