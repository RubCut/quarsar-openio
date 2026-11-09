# Исправления аудита commit 55599dc

Вердикт аудита сохранён: вредоносной логики, телеметрии, майнера или удалённого
управления не обнаружено; скрипты по умолчанию dry-run; в production-коде нет
`system()`/`popen()`. Установка root-демоном до исправлений не рекомендовалась.
Ниже — что изменено и что остаётся сознательным остаточным риском.

## Высокие риски

| Находка | Исправление | Проверка |
|---|---|---|
| Audio 19080/19081 без авторизации | Bearer token 64 hex из `/dev/urandom`, файл `0600` в приватном каталоге `0700`; PCM `AUTH <token>\n` до payload; HTTP `Authorization: Bearer` на каждый запрос; constant-time compare;отказ при duplicate/control bytes | `tests/run.py`: 401 без/с неверным токеном, PCM reject, accepted_bytes=0, authenticated producer |
| Installer брал host `build/static` | По умолчанию `build/aarch64`; `scripts/verify_aarch64.sh` проверяет ELF64 LE AArch64 ET_DYN, отсутствие PT_INTERP и DT_NEEDED, SHA-256 pin; apply требует 4 pins | `tests/test_install.py`: wrong arch, non-PIE, INTERP, NEEDED, hash mismatch |
| Plugin root в домене демона | Отдельный `quasar_plugin` domain/exec type, transition без `execute_no_trans`; plugin получает только fb4/fb5 и pipe; опциональный fail-closed `--plugin-uid/--plugin-gid`, `PR_SET_NO_NEW_PRIVS`, закрытие всех inherited fds | policy review; `test_plugin.py`: fd200 closed, privilege option validation; SELinux на устройстве не проверен |

## Подтверждённые баги

1. **CPU spin после закрытия audio pipe.** `pack/audio.c` делает bounded drain
   8×4096 и ставит EOF на HUP/ERR даже без POLLIN. Регрессия:
   `test_audio_pipe_hup_does_not_spin` измеряет CPU ticks живого процесса.
2. **HTTP consumer держит слот вечно.** Streaming lease 15 с продлевается только
   при реальном send-прогрессе; request/auth deadlines абсолютные. Регрессия:
   idle consumer отключается, следующий получает 200.
3. **9-я IPC команда ждала нового ввода.** `process_lines()` оставляет остаток в
   буфере, `qio_ipc_tick()` drains pending, main ставит `timeout=0`. Регрессия:
   9 status-команд одним write → 9 ответов без дополнительного ввода.
4. **Plugin наследовал fd > 127.** Snapshot `/proc/self/fd` через getdents64 до
   снижения RLIMIT_NOFILE, fallback close_range/bounded sweep. Регрессия: fd 200,
   переданный демону, закрыт в ребёнке.
5. **OTA удалял чужую пустую `QIO_OTA_*`.** Владение = маркированный OUTPUT jump +
   непустая цепочка, где все правила маркированы; чужие ссылки запрещены.
   Регрессии: empty foreign, stale without jump, foreign reference.
6. **`\u0000` проходил JSON.** Decode escape rejects NUL. Регрессии в
   `tests/test_core.c`: action/key/value с `\u0000`.
7. **Install non-transactional.** Staging + re-hash + backup/move + rollback trap +
   install journal; refuse running daemon/plugin. Регрессии: commit, rollback,
   interrupted journal, symlink target.

Дополнительно: повторная анимация перезапускает ребёнка с полной длительностью;
Unix listener keep-alive обновляется при реальной доставке событий; root runtime
files вынесены в приватный `0700` каталог; host/static builds PIE + full RELRO +
BIND_NOW + non-exec stack; CI добавлен.

## Остаточные риски и границы

- **SELinux — шаблон, не проверенный модуль.** Домены/метки должны быть
  адаптированы под реальный ROM и загружены до старта. Host-тесты не доказывают,
  что политика соберётся и что kernel разрешит mmap/ioctl/pipe. Без политики
  сервис должен fail-closed; `setenforce 0` запрещён.
- **Root-демон остаётся привилегированным.** Separate plugin domain и optional UID
  drop уменьшают blast radius, но не превращают супервизор в непривилегированный
  процесс. DAC drop требует проверки доступа плагина к fb4/fb5.
- **Bearer token — локальная capability.** Процесс с тем же UID/root или доступом
  к 0600-файлу может inject/read audio. Это не защита от скомпрометированного
  доверенного UID. Token не передаётся в argv примерами.
- **Installer не A/B и не power-loss atomic.** Rollback покрывает ошибки команд;
  потеря питания может оставить journal/backup files. Следующий apply refused до
  ручного восстановления. Оператор обязан иметь план отката `/odm`.
- **Docker base digest задаёт оператор.** В репозитории нет непроверенного digest.
  Dockerfile требует `ALPINE_IMAGE` с `@sha256:`; без него сборка не должна
  запускаться. Непропиненный privileged binfmt-контейнер не рекомендуется.
- **OTA firewall — snapshot адресов.** CDN DNS меняется, адреса могут быть общими.
  Предпочтителен проверенный `--uid`; существующие sockets/downloads не удаляются.
- **PID reuse.** Linux 4.9 без pidfd: starttime/boot_id/cmdline уменьшают, но не
  устраняют математически узкое окно между проверкой и `kill()`.
- **Framebuffer ownership.** `SIGSTOP` не закрывает mmap/fd штатного SDK;
  одновременный вывод возможен только если драйвер это допускает. Геометрия не
  угадывается, padding не пишется, `FBIOPUT_*`/write/msync/pan не используются.
- **CI не заменяет on-device soak.** CI проверяет host/sanitizers и AArch64 ELF,
  но не Amlogic LED, SELinux enforcing, поведение `io.sdk` и штатного плеера.

## Рекомендуемый порядок развёртывания

1. `make test`, `make sanitize`, shellcheck, `make aarch64`, verify ELF/hashes.
2. Read-only inventory устройства: mounts/verity, `ls -Z`, `getevent`, `ps -Z`.
3. Адаптировать/загрузить SELinux policy с separate plugin domain; enforcing.
4. Остановить daemon/plugin; проверить backup/recovery план `/odm`.
5. Hash-pinned `install_odm.sh --apply --confirm-odm-unprotected`.
6. Первый кадр минимальной яркости после `pause_sdk`; сразу проверить `resume_sdk`,
   кнопки, микрофон, ring, kernel warnings.
7. Audio token/timeouts и soak под нагрузкой; только затем обычный boot trigger.
