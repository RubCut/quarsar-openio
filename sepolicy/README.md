# SELinux: справочная политика

`init/quasar_openio.rc` использует `seclabel u:r:quasar_openio:s0`. Установщик
ставит супервизору `u:object_r:quasar_openio_exec:s0`, а стандартному плагину —
`u:object_r:quasar_plugin_exec:s0`; при exec плагин переходит в отдельный домен
`u:r:quasar_plugin:s0`. Runtime-файлы в `/data/local/tmp/quasar-openio/` должны
иметь `u:object_r:quasar_openio_data_file:s0`. Этих типов нет в стоковой политике
Android. Без заранее загруженной политики сервис должен не запуститься, а не
обходить SELinux.

Файлы здесь — **шаблон для адаптации**, а не универсальный готовый модуль:

- `quasar_openio.te` — AOSP-style правила: супервизор, отдельный plugin-домен,
  приватный runtime-каталог; `execute_no_trans` для плагина не выдаётся;
- `file_contexts.snippet` — контексты для `/odm` и runtime-каталога.

Перед адаптацией снимите read-only контексты реального устройства:

```sh
ps -Z | grep com.yandex.io.sdk
ls -Z /dev/graphics/fb4 /dev/graphics/fb5 /dev/input/event0 /dev/input/event2 /dev/input/event4
ls -Z /data/local/tmp /data/local/tmp/quasar-openio /odm/bin /odm/etc/init /odm/lib/quasar/plugins/standard
cat /proc/self/attr/current
```

В шаблоне домен SDK условно указан как `priv_app`. Замените его на фактический
домен из `ps -Z`. Метки `/proc/<pid>/cmdline`, `/proc/<pid>/stat`,
`/proc/sys/kernel/random/boot_id`, input/graphics устройств и `/data/local/tmp`
зависят от ROM; проверяйте их, а не угадывайте. Плагину нужны только fb4/fb5 и
pipe супервизора; не добавляйте ему input, network, SDK signal, token/socket/lock/
journal доступ. Опциональный `--plugin-uid/--plugin-gid` требует отдельных
`setuid/setgid` capability у супервизора и проверки DAC на graphics-устройствах.

Загрузка политики зависит от root-решения: сборка device policy, KernelSU/magiskpolicy
на раннем старте или другой подтверждённый механизм. Политика должна быть активна
до запуска init-сервиса. Live-правила могут не пережить перезагрузку. Не используйте
`setenforce 0` и не добавляйте широкий `permissive` домен ради запуска.

OTA-скрипт вызывает `iptables`/`ip6tables` и требует отдельных сетевых/admin
разрешений. Этот шаблон намеренно не выдаёт демону `net_admin` и не запускает
firewall-скрипт из горячего пути.
