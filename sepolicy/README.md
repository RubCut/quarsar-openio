# SELinux: справочная политика

`init/quasar_openio.rc` использует `seclabel u:r:quasar_openio:s0`, а установщик
ставит бинарнику контекст `u:object_r:quasar_openio_exec:s0`. Этих типов нет в
стоковой политике Android. Без заранее загруженной политики сервис должен не
запуститься, а не обходить SELinux.

Файлы здесь — **шаблон для адаптации**, а не универсальный готовый модуль:

- `quasar_openio.te` — AOSP-style правила с макросами device-политики;
- `file_contexts.snippet` — контексты для `/odm`.

Перед адаптацией снимите read-only контексты реального устройства:

```sh
ps -Z | grep com.yandex.io.sdk
ls -Z /dev/graphics/fb4 /dev/graphics/fb5 /dev/input/event0 /dev/input/event2 /dev/input/event4
ls -Z /data/local/tmp /odm/bin /odm/etc/init
cat /proc/self/attr/current
```

В шаблоне домен SDK условно указан как `priv_app`. Замените его на фактический
домен из `ps -Z`. Метки `/proc/<pid>/cmdline`, `/proc/<pid>/stat`,
`/proc/sys/kernel/random/boot_id`, input/graphics устройств и `/data/local/tmp`
зависят от ROM; проверяйте их, а не угадывайте.

Загрузка политики зависит от root-решения: сборка device policy, KernelSU/magiskpolicy
на раннем старте или другой подтверждённый механизм. Политика должна быть активна
до запуска init-сервиса. Live-правила могут не пережить перезагрузку. Не используйте
`setenforce 0` и не добавляйте широкий `permissive` домен ради запуска.

OTA-скрипт вызывает `iptables`/`ip6tables` и требует отдельных сетевых/admin
разрешений. Этот шаблон намеренно не выдаёт демону `net_admin` и не запускает
firewall-скрипт из горячего пути.
