# Аудио-мост и `appsrc name=stream-src`

## Что реализовано

`quasar_openio` принимает сырой PCM по TCP `127.0.0.1:19080` и отдаёт его по HTTP
`127.0.0.1:19081`. Формат фиксирован: `S16LE`, 48000 Hz, stereo. Буфер ограничен
262144 байтами, переполнение вытесняет старые кадры, второй producer и второй
streaming-consumer отклоняются. Всё это находится в одном `poll()`-цикле демона,
без потоков и неограниченных очередей. `fork()` используется только супервизором
при смене режима плагина — никогда на кадр или на аудио-чанк. PCM дополнительно
зеркалится в pipe визуализатора (`--audio-fd 3`) короткими неблокирующими
записями не больше 4096 байт; при переполнении pipe чанк отбрасывается.

Это безопасный транспортный слой. Он **не** утверждает, что штатный плеер уже
подключён к HTTP, и **не** внедряется в чужой GStreamer pipeline.

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

```sh
gst-launch-1.0 \
  tcpclientsrc host=127.0.0.1 port=19080 ! \
  rawaudioparse format=pcm pcm-format=s16le sample-rate=48000 num-channels=2 ! \
  audioconvert ! audioresample ! autoaudiosink
```

Или через HTTP:

```sh
gst-launch-1.0 \
  souphttpsrc location=http://127.0.0.1:19081/audio.wav ! \
  wavparse ignore-length=1 ! audioconvert ! audioresample ! autoaudiosink
```

На Android sink может быть `openslessink`/`aaudiosink` в зависимости от сборки
GStreamer. Streaming WAV с неизвестной длиной поддерживается не всеми
элементами; `ignore-length` и поведение при EOF нужно проверить. Этот вариант
обходит монополию `io.sdk` только если штатный pipeline не держит нужный ALSA
subdevice или корректно уступает audio focus.

## Вариант C: штатный плеер забирает loopback HTTP

Демон уже отдаёт `http://127.0.0.1:19081/audio.wav` и `/audio.pcm`. Чтобы штатный
плеер использовал этот URL, нужен подтверждённый способ передать URI плееру:
Intent, Binder-сервис, локальный API или конфигурация. В рамках этого репозитория
такой способ не подтверждён.

Проверять нужно на устройстве:

- принимает ли плеер `http://127.0.0.1` и MIME `audio/wav`;
- поддерживает ли chunked/close-delimited WAV с длиной `0xffffffff`;
- какой сервис/Intent меняет источник без перезапуска всего пакета;
- не блокирует ли SELinux loopback-соединение;
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
