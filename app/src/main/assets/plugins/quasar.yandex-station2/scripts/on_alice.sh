#!/system/bin/sh
# Пример обработчика события. Сервис передаёт JSON на stdin, а строки stdout
# разбирает как команды. Здесь только уведомление в журнал, без изменения экрана.
read event
echo '{"notify":"Alice button pressed"}'
