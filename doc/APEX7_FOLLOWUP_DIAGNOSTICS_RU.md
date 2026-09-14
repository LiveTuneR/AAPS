# Follow-up: диагностическая сборка

Исходный HEAD: `b33828d43d1804ad87f6fbb249f503c11645b195`.
Предыдущий подписанный APK: `30f58c7572161a72d38029803b9da608ae12083c`.
Дата: 12 сентября 2026. Статус всей задачи: **PARTIAL**, не клиническая приёмка.

## Реализовано

- Экспорт и retention используют одну сортировку: активный лог, дата имени,
  числовой индекс ротации. `.1000` больше `.100`, `.100` больше `.99`.
  Для неизвестного имени применяется mtime с детерминированным разрешением равенств.
  Отрицательные amount/keep трактуются как 0. amount=0 даёт только manifest;
  keep=0 удаляет архивные логи, но не активный AndroidAPS.log.
- Снимок экспорта ограничен размером файлов на момент открытия дескрипторов,
  общим лимитом 256 MiB и пределом проверки распакованного содержимого 2 GiB.
  Дописывание активного лога не продлевает чтение до движущегося EOF.
  Это согласованность отдельного файла, НЕ глобальный атомарный снимок логгера.
- ZIP проверяется до передачи, SAF-копия проверяется SHA-256 повторным чтением.
  При ошибке экспорт возвращает неуспех; неполный архив не передаётся на отправку.
  Снимок очищается в finally, retention не удаляет чужую временную выгрузку.
- manifest.json: SHA приложения, размер/хеш файлов, явные epoch при наличии ISO
  timestamp, offset/timezone экспорта, порядок выбора, лимиты, пробелы индексов.
  Старые HH:mm:ss логи не получают выдуманную дату/зону. Полнота дня и потери
  логгера не предполагаются. Дата в имени является локальным wall time;
  экспортная зона не считается исторической зоной файла.
- Новый префикс логов содержит дату и UTC offset. RxBus больше не вызывает
  event.toString. UKF не печатает повторно все исторические точки. Математика
  сглаживания не изменялась.
- Убран дополнительный dev HTTP BODY-interceptor Tidepool. Из callback-ошибок
  исключены произвольные сообщения сервера/исключений; сохраняются код/тип.
  Исключены dataset IDs из двух сообщений. Полный HTTP call ограничен 60 s.
  UploadProgress различает SUBMITTED, HTTP_ACK и FAILED, пишет окно/байты/checkpoint.
- AlgorithmDecisionSnapshot создаётся в реальных ветвях OpenAPS SMB.
  Текущий dynamic ISF, future ISF и ISF делителя insulinReq разделены.
  Записаны условия SMB, high TT, прогнозируемое снижение, чрезмерная delta,
  invalid input, IOB cap, maxBolus и ожидание интервала. UAM не выдаётся за SMB eligibility.
  Snapshot не входит в сериализованный RT и не меняет dosing transport.
- TherapyDecision различает ограничения, вход в apply и ответ драйвера TBR/SMB.
  success, enacted, queued и reportedDeliveredU записываются раздельно.
  APPLY_ENTER НЕ означает запись по BLE; APPLY_RESULT НЕ является самостоятельным
  доказательством физической доставки. Сверять с trace драйвера и историей помпы.
- WorkflowDecision показывает claim BG, возврат invoke и прерывание. CalculationTiming
  показывает generation, монотонную длительность, ADS cache hits/misses и агрегированные
  затраты load/smoothing/profile/treatments/COB/sensitivity/graph. Вложенные времена
  нельзя суммировать как независимые; это профилирование, не watchdog.
- Копирование ADS теперь разделяет GV/IDs, bucketed values, AutosensData,
  вложенные carbs, extraDeviation и AutosensResult. Служебные зависимости строк
  сохраняются, математические значения копируются без изменений.
- В Enhanced Overview больше нет подмены dosing ISF полем variableSens.
  Добавлены реальные поля условий SMB, интервала, cap, requested/constrained/delivery.
  Для неподдерживаемого алгоритма или недостигнутой ветви показывается unknown.

## Автопроверки

`DetermineBasalSMBTest`: до инструментирования на исходном HEAD записан SHA-256
768 полных сериализованных результатов; после правок сравнивается та же матрица:
dynamic on/off, COB=0/>0, TT absent/low/high, 16 комбинаций preferences,
свежий/старый bolus, IOB выше/ниже лимита. Эталон:
`727dd06bbab8d19c18dc4ce7b5a980a8d735e16803c581261ed9eb0042f058f0`.
Это 768 сценариев внутри одного JUnit-теста, а не 768 отдельных новых тестов.
Отдельно проверяются reason codes и unknown для раннего отказа CGM.

`LogArchiveTest`: дефект старой лексикографической сортировки, несколько дат,
append/rename/truncate, отсутствующий/повреждённый файл, ошибки потоков,
хеши, timezone, legacy unknown, gaps, amount=0. `MaintenanceImplTest`: keep=0/1.
`RxBusLoggingTest`: toString события бросает исключение; доставка всё равно работает.
`AutosensDataStoreTest`: изменение старых строк/списков/IDs не меняет копию.

В CI дополнительно запускаются Medtrum, APS, smoothing, maintenance и RxBus tests.
Validation init отключает cache/up-to-date только для Test задач; компиляционный
cache разрешён. Подпись CI обязана совпадать с обычным 3.4.2.3 APK:
`1b20d5c3807e9e6d895728d68099e21801ec05f860d4cc457eee25e530a8a084`.
Точные результаты и артефакт публикуются отдельным release receipt после завершения CI.

Локальный полный выбранный прогон: **1502 tests / 0 failures / 0 errors / 0 skipped**,
3 min 51 s. 212 JUnit XML, 13 модулей; это не все тесты приложения.
Разбивка: workflow 13, plugins/main 27, sensitivity 58, core/interfaces 51,
sync 843, Apex 25, core/data 32, Medtrum 144, APS 288, smoothing 8,
maintenance 9, RxBus 1, UI 3. 768 oracle-сценариев входят в APS 288.
Полный failures list финального прогона пуст. При снятии эталона был один
ожидаемый failure `BASELINE_PENDING`; это не итоговый регрессионный результат.

## Незакрытые критерии

| Раздел исходного follow-up | Статус | Ограничение |
| --- | --- | --- |
| A: логи/retention | PARTIAL | Основной экспорт исправлен; полный audit Tidepool retry/partial progress и измерение байтов за 48 h не завершены |
| B: владение данными | PARTIAL | Глубокие копии строк есть; load/smoothing ещё используют live ADS, нет общей ревизии profile/treatments/BG |
| B: bounded single-flight | NOT IMPLEMENTED | REPLACE остаётся; непрерывный 60 s поток при 75/120/180 s расчёте ещё может вытеснять работу |
| B: claim/enact | PARTIAL | Есть стадии в логах; cancellation-safe retry watermark и durable uncertain-delivery ledger не реализованы |
| C: сетка/metadata/pruning | PARTIAL | ADR ниже; частота и filtering не менялись, полноценного backport нет |
| D: объяснимость | PARTIAL | Реальные SMB ветви и exact-output regression; atomic generation provenance, persistent snapshot и полная ACK-модель ещё отсутствуют |
| E: Enhanced Overview | PARTIAL | Уточнены ISF/SMB поля; исходный полный макет и production provider/state matrix не завершены |
| F: activity | BLOCKED | Нет принятого SDK/bridge и настоящей записи часов; не выдавать контракт SamsungExerciseReader за интеграцию |
| G: обе помпы | PARTIAL | Запуск unit-тестов; новые полные service/queue сценарии и Nano 200U hardware acceptance не проведены |
| Offline meal replay | BLOCKED | Нет полных 24–48 h decision traces; дозовые рекомендации не формировались |
| Installation/migration | BLOCKED | Установленный пакет, pairing, Room migration и история на телефоне не проверены |

Не изменялись SMBInterval, лимиты, preferences терапии, формулы, firmware allowlist,
experimental gates, pump FSM и частота dosing. Новый минутный dosing path не добавлен.
Скриншоты прежнего CI остаются минимальными synthetic fixtures, не доказательством
приёмки полного нового dashboard. Настоящие device screenshots не получены.

## Сбор на телефоне

Root и постоянно подключённый ADB не нужны: это штатные файлы приложения.
Категории APS, WORKER, PUMP/PUMPCOMM/PUMPQUEUE и TIDEPOOL должны быть включены
в настройках логирования; они включены по умолчанию, но сохранённые настройки
пользователя не перезаписываются. Для компактных CalculationTiming не требуется
включать шумный AUTOSENS. Выгрузка выполняется штатным экспортом логов.

Перед отправкой проверить manifest: сколько файлов/какой период фактически попал.
Архив содержит чувствительные медицинские данные; передавать приватно, не в public git.
Потеря процесса/питания может оборвать последнюю строку; отсутствие ACK в логе
не доказывает отсутствие доставки и не является основанием повторить болюс.

## Миграция и откат

Перед установкой сверить сертификат реально установленного пакета, экспортировать
настройки и резервную копию данных. APK не проверен Android installer на телефоне.
Не удалять приложение ради обхода несовпадения подписи. Совпадение сертификата с
предоставленным 3.4.2.3 не доказывает совпадение с установленным пакетом.
Старая версия может не открыть новую БД: откат требует совместимого резервного
состояния, а не только старого APK. Первые pump integration испытания проводить
на стенде без подключения к человеку. Эта сборка не принята для живого closed loop.
