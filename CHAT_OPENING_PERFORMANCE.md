# Открытие чатов: сравнение и исправления

Проверено 2026-10-10. Исходный Xenon: `09c53fdbc`, Telegram:
`f2908b141` (`DrKLO/Telegram`), Nekogram: `577d3924c` (`nekogram/main`).
Все три версии используют Telegram 12.10.6 (7112). HEAD апстримов проверены
через `git ls-remote`, Nekogram обновлён через fetch. Сравнение учитывает
предыдущую оптимизацию Xenon `4fae099fc`.

Пользователь сообщил о спайке при каждом входе, предположительно с выключенными
Blur Fade и Progressive Fade Blur. Поэтому дополнительные fade-захваты сами
по себе не объясняют этот случай. Найдены и исправлены также операции,
выполняющиеся без Fade. Единственная причина наблюдаемого спайка и его
длительность без профиля устройства не установлены.

## Что изменено

| Путь | До исправления | После |
| --- | --- | --- |
| Liquid/prism/highlight | При пустом пуле `new RuntimeShader` вызывался синхронно из отрисовки | Создание только на `GlassShaderWarmup`; до готовности сохраняется обычный размытый фон с тонировкой; callback обновляет поверхность |
| Progressive Fade Blur | Два новых RuntimeShader при первом захвате каждого нового источника на UI-потоке | Фоновый пул независимых экземпляров по качеству; до получения обоих используется платформенный blur |
| LiquidTouchEffect | Каждый экземпляр сразу создавал три SpringAnimation; reset перерисовывал даже нетронутый элемент | Пружины создаются при первом попадании ACTION_DOWN; idle reset не запрашивает кадр |
| Обои liquid/frosted | Одинаковые обои записывались в два отдельных полноэкранных blur-узла | Оба источника используют один wallpaper RenderNode; изменения и размеры передаются владельцу |
| Обработка `messArr` | Блок финализации загрузки повторялся внутри цикла сообщений и после него | Оставлен блок после цикла, как в обоих апстримах |
| AOSP transition | Первый захват края рисовал контейнер до обычного прохода | Захват после обычного drawChild использует уже записанные дочерние display lists |
| Плагины | Lua `onChatMenuBuild` выполнялся в `ChatActivity.createView()` | Выполняется при первом открытии меню, перед раскладкой lazy items |
| Fade при открытии | Дополнительный захват списка выполнялся ещё во время перехода | Обычный wallpaper fade до завершения открытия; затем включается выбранный blur |
| Fade invalidation | Даже изменение только положения стеклянной поверхности перезаписывало весь список и обои | Position-only не запускает захват Fade; сообщения и обои имеют отдельное dirty-состояние |
| Fade lifecycle | Цикл оставался активным для приостановленного чата | Остановка в onPause, возобновление в onResume |

Основные файлы: `ChatActivity.java`, `LiquidTouchEffect.java`,
`blur3/GlassShaderCache.java`, `blur3/LiquidGlassEffect.java`,
`blur3/source/BlurredBackgroundSourceRenderNode.java`,
`blur3/drawable/BlurredBackgroundDrawableRenderNode.java`,
`ActionBar/ActionBarLayout.java`, `ActionBar/ActionBarMenuItem.java`.
Пути Java указаны относительно `TMessagesProj/src/main/java/org/telegram/ui/`.

Shader экземпляры не разделяют изменяемые uniforms между поверхностями.
При асинхронной готовности они инициализируются даже без изменения геометрии.
При отказе prism-программы запрашивается исходная программа; при отказе
остальных сохраняется fallback. Анимированные обои продолжают инвалидировать
запись через `onDescendantInvalidated(backgroundView, ...)`.

## Ошибка загрузки сообщений

Вставка `09f942b08` продублировала внутри цикла `messArr`
`checkGroupMessagesOrder()`, сброс unread IDs, изменение `loadsCount`,
флагов конца истории и потенциальный `notifyItemRemoved()`.
Это отличается от обоих апстримов и неправильно финализирует ещё не
обработанную пачку. Для reversed feeds проверка порядка дополнительно
обходила растущий список на каждой итерации. В обычном чате метод сразу
возвращает при `reversed == false`: приписывать ему такой же обход нельзя.

## Проверки

15 целевых тестов прошли: glass (10), progressive blur (3),
chat opening performance (2). Тесты исполняют извлечённые production-методы
или реальный класс кеша с Android-заглушками. Проверены холодный кеш,
фоновое создание, дедупликация уведомлений, отказ компиляции, независимость
shader экземпляров, частичная готовность progressive-пары, fallback,
инициализация uniforms, общие обои, обновления контента и lifecycle Fade.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_glass*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_progressive_blur.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_chat_opening_performance.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tests/chat_fade_capture_audit.py
PYTHONDONTWRITEBYTECODE=1 python3 tests/chat_fade_capture_audit.py 09c53fdbc
./gradlew :TMessagesProj_App:assembleRelease --offline
```

Стенд Fade подаёт 120 отдельных инвалидаций с интервалом 9 мс:

| Сценарий с включённым Fade | Захваты сообщений до → после | Рисования обоев до → после |
| --- | ---: | ---: |
| Только позиции стекла | 120 → 0 | 120 → 0 |
| Изменения списка / прокрутка | 120 → 120 | 120 → 1 |
| Изменения обоев | 120 → 120 | 120 → 120 |

Результат одинаков для обычного и progressive Fade. С выключенным Fade
в обоих вариантах 0 захватов. Это проверка планирования вызовов, не замер FPS
и не утверждение, что реальное открытие создаёт 120 инвалидаций.

Общий unittest discovery обнаружил 2 failures и 7 errors в трёх старых
модулях: `test_folder_background`, `test_update_changelog`,
`test_telegram_release`. На исходном `09c53fdbc` те же модули дают те же ошибки
(устаревшие заглушки Drawable/Canvas/TextUtils и старый API build_message).

## Границы результата

Release-сборка `assembleRelease --offline` прошла; подпись APK проверена
через `apksigner verify`. ADB-устройства нет. APK находится в
`TMessagesProj_App/build/outputs/apk/release/Xenon-12.10.6-7112-arm64-v8a.apk`.
Фактические FrameTimeline / main thread / RenderThread / GPU на телефоне
не измерены. Конструктор RuntimeShader теперь выполняется вне UI-потока,
но это не доказывает отсутствие затрат драйвера при первом аппаратном draw.

Четыре `CountDownLatch.await()` при отсутствии user/chat в памяти есть также
в обоих апстримах и без профиля не являются доказанной регрессией Xenon.
Предыдущий feedback через счётчик window pre-draw уже был устранён `4fae099fc`.
`removeChatDelay` способен переместить обработку первой пачки внутрь перехода;
его семантика не менялась без данных устройства.

На телефоне остаётся проверить повторные входы в одинаковый чат сначала
с текущими настройками пользователя, затем отдельно Fade / progressive,
AOSP transition и плагины. В Perfetto различать задержку main thread,
RenderThread и GPU; отчёт и стенды не заменяют эти измерения.
