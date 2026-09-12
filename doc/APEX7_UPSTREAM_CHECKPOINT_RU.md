# Apex7: перенос исправлений, контрольная точка 12.09.2026

Статус: PARTIAL / EXPERIMENTAL_NOT_DEVICE_VALIDATED. Это не разрешение на использование замкнутого цикла.

## Основание

Начальный custom SHA: `9da7e34c567aac306b2d98fa87f138b121c01eb2`.
После `git fetch origin dev` проверен upstream `5d2852b72f14749daf3379cb3ffc2dc620465f11`; новых коммитов после указанного в ТЗ SHA нет.
Матрица до редактирования: `APEX7_UPSTREAM_20260912.md`.
Репозиторий: LiveTuneR/AAPS, ветка `codex/apex7-reliability-overview`.

## Поправка пользователя имеет приоритет

Постоянный якорь upstream #5066 НЕ перенесён. Начатый перенос отменён до коммита.
`referenceTime` принадлежит одному проходу и сбрасывается в `finally`; `clone()` его не копирует.
Исторический аналитический шаг остаётся пятиминутным. Новые реальные BG 60/120 с становятся текущими без округления к общей сетке часов.
Формулы инсулина в рамках CGM не менялись. Отдельное изменение #5082 ниже прямо запрошено другим пунктом ТЗ.

## Выполнено

- #5082: обе активные реализации SMB/AutoISF используют `basal * (30 - durationReq) / 30`; DynISF проходит через SMB. Изменены только соответствующие выражения, сохранены округление и ограничения.
- #5100: участок TBR -> SMB защищён от отмены расчёта. Перед входом проверяется cancellation; расчёт и constraints остаются отменяемыми. Открытое подтверждение также записывает результат после отмены UI-scope. Неуспешный SMB заменяет состояние QUEUED; автоматических повторов болюса не добавлено. Старый fallback остаётся temp-basal-only.
- e88ae5d9: отказ от неполного TDD до любых новых записей дневного кэша. `allowMissingDays` сохранён.
- 872d1ab3: очистка TDD перенесена в общий путь инвалидирования, включая прямой EPS; граница равна полуночи затронутого дня.
- 985eede5: false при активации инсулина передаётся существующему `onError`. Повторный confirm не исполняет операцию заново. Сам тип ConfirmResult в старой архитектуре всё ещё содержит Delivered/NoPending; об ошибке получатель узнаёт через callback, как в upstream.
- Метаданные GV: пропуск только при равенстве отдельному глубокому снимку успешно опубликованного ADS. Незавершённый clone не имеет такого доказательства; неизвестные/изменённые строки пересчитываются, смешанный insert + NS writeback не теряется.
- e50cf655: удаление ADS-строк строго старше окна текущего запуска; удаление с конца, без пропуска соседних элементов. Не привязано к текущим часам телефона.
- Диагностика: `BucketPassEvidence` хранит использованный якорь только как неизменяемые данные, не как состояние следующего прохода. `CgmDecision` связывает raw/bucket timestamp, поколение, публикацию, вызов Loop и причину отклонения. `CalculationTiming` содержит start/finish и длительности фаз.
- Публикация и пропуск ADS записываются в `WORKER`, отказ/вызов Loop в `APS`. Обе категории нужно оставить включёнными; включать шумный `AUTOSENS` для этих записей не требуется. Пропущенная публикация получает отдельный `ADS_PUBLISH_SKIPPED`.
- UI: адаптивная надпись dISF при DynISF, данные current/future/dosing ISF разделены; TDD, делитель, adjustment factor добавлены в детали. Время активности справа обновляется UI-таймером, без ежесекундного чтения БД/SDK. Статусы находятся в одной строке, ON/WAIT/OFF/UNKNOWN определяются структурированными полями. Возраст Loop берётся от завершённого расчёта. При крупном шрифте строка прокручивается горизонтально, ширина легенды увеличивается.
- Сохранены числовая сортировка ротаций, ограниченный экспорт ZIP, отсутствие Wear payload и Tidepool BODY в штатных логах. Добавлен синтетический замер объёма EVENTS для графика из 1440 точек каждую минуту.
- GitHub CI включает новые TDD/активация/CommandSMBBolus тесты, а не только старые проверки архивов.

## Проверки CGM

`FastCgmLoopBoundaryTest`: реальные AutosensDataStoreObject, actualBg, WorkflowChainData и PostCalculationWorker; только Loop и периферийные сервисы замоканы. Виртуальный clock передан в store, production по умолчанию использует System.currentTimeMillis.
Пять сценариев: 60 с, 120 с, оба варианта с пропущенной публикацией старого поколения, штатные 5 мин. Поток 30 виртуальных минут; каждый успешный BG доходит до вызова Loop; повтор того же поколения/BG не вызывает второй Loop.
Это НЕ полное исполнение IOB/COB worker pipeline и НЕ аппаратная проверка.

Мутации (ошибочные версии не входят в сборку):

| Мутация | Anchor suite | Loop boundary suite |
|---|---:|---:|
| M1: копировать referenceTime в clone | 1/9 падает | 0/5 падает: finally ещё действует |
| M2: отключить сброс в finally | 5/9 падают | 2/5 падают: same-live-store |
| M3: сохранить якорь и копировать его | 8/9 падают | 5/5 падают |

M1 проверяется отдельным тестом контракта clone со специально установленным незавершённым якорем. Обычный завершённый проход уже обнулён, поэтому исключительно потоковый тест не может обнаружить M1 сам по себе. M2 реализована удалением операции сброса; оболочка finally для диагностического снимка оставалась.
После мутаций восстановлен рабочий код и выполнен повторный прогон. Исходные ошибочные прогоны не считаются acceptance.

## Replay

Сохранён независимый digest 768 полных сериализованных результатов из прежнего неизменённого набора: `727dd06bbab8d19c18dc4ce7b5a980a8d735e16803c581261ed9eb0042f058f0`.
Он не достигает исправленной low-temp ветки и доказывает отсутствие изменений в этом наборе, но не эквивалентность всех клинических ситуаций.
Новые детерминированные high-IOB/COB=20 сценарии достигают реальной ветки SMB, DynISF и AutoISF для 1/15/29 мин. При базале 1 Е/ч результат после штатного округления: 0.97/0.50/0.03 Е/ч на 30 мин. Ожидаемая доза базала отличается от идеальной не более 0.0026 Е; точное аппаратное округление конкретной помпы не проверено.
Причина изменения относительно старой формулы: требуется удержать инсулин на durationReq минут, а не доставить его за эти минуты. Логи `TherapyReplay` содержат IOB, старую и новую скорость.

## Не завершено

1. Latest-pending/coalescing планировщик НЕ реализован. Существующие characterization-тесты по-прежнему доказывают starvation при REPLACE и расчётах 70/90/120 с под входом 60 с. Нельзя заявлять гарантированный Loop каждую минуту при такой производительности. Нужна общая модель жизненного цикла worker/history, упорядочивания therapy events и безопасного завершения enactment; простая замена REPLACE на KEEP неверна.
2. Полная replay-матрица meal/late meal/WAIT/temp target/profile-switch с end-to-end APS/pump не завершена. Прямая проверка EPS подтверждает очистку TDD, но не весь новый DynISF результат после загрузки профиля.
3. AutoISF ещё не имеет всей структурированной instrumentation SMB; там нельзя выдать достоверные ON/WAIT/OFF и итоговый factor при отсутствующих данных. Показывается UNKNOWN. Полный набор pending/coalesced counters, границы constraint/enactment в одном snapshot также не завершён.
4. Защита #5100 проверена на generic mock command queue; физические Apex/Medtrum и отмена в реальном BLE/DB цикле не проверены. Существующие драйверные тесты сохранены, но не заменяют эти проверки.
5. SamsungExerciseReader остаётся adapter contract, реальный SDK-доступ не подключён. ActivityContext остаётся SHADOW.
6. UI ближе к референсу, но не является его точной копией: сохранён существующий Vico, не добавлены внешние панели навигации референса. Скриншоты являются настоящим Compose-компонентом на синтетических данных, а не фото телефона. EN fixture ещё содержит некоторые синтетические RU значения.
7. Замер EVENTS не является общим MB/час приложения; реальный многoчасовой бюджет всех категорий и ротаций требует логов телефона.

## Уровни подтверждения

Полный локальный прогон `build/apex7-upstream-full-final.log`: BUILD SUCCESSFUL, 3 мин 34 с. Всего 2003 теста, 0 failures/errors/skipped.

| Модуль | Тесты |
|---|---:|
| workflow | 22 |
| plugins/main | 40 |
| sensitivity | 58 |
| core/interfaces | 51 |
| plugins/sync | 843 |
| Apex | 25 |
| core/data | 32 |
| Medtrum | 144 |
| plugins/aps | 295 |
| smoothing | 8 |
| implementation (выбранные suites) | 78 |
| shared/impl (RxBus) | 2 |
| ui (все suites) | 405 |

Команда: `gradlew.bat :workflow:testFullDebugUnitTest :plugins:main:testFullDebugUnitTest :plugins:sensitivity:testFullDebugUnitTest :core:interfaces:testFullDebugUnitTest :plugins:sync:testFullDebugUnitTest :pump:apex:testFullDebugUnitTest :core:data:test :pump:medtrum:testFullDebugUnitTest :plugins:aps:testFullDebugUnitTest :plugins:smoothing:testFullDebugUnitTest :implementation:testFullDebugUnitTest --tests '*LogArchiveTest' --tests '*MaintenanceImplTest' --tests '*TddCalculatorImplTest' --tests '*WizardBolusExecutorImplTest' --tests '*CommandSMBBolusTest' :shared:impl:testFullDebugUnitTest --tests '*RxBusLoggingTest' :ui:testFullDebugUnitTest --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -I doc/apex7-validation.init.gradle --console=plain`.

Синтетический EVENTS-замер: 21 515 280 байт/час при прежнем payload stringification против 1 860 байт/час текущего type-only сообщения. Исключены остальные категории, префиксы logback и реальная компрессия; это не общий объём логов приложения.

- VERIFIED BY STATIC/UNIT TEST: см. JUnit и отдельный build receipt с фактическими числами.
- VERIFIED BY REPLAY: ограниченные детерминированные сценарии выше, не вся требуемая матрица.
- VERIFIED ON ANDROID DEVICE: нет.
- VERIFIED WITH APEX HARDWARE: нет.
- VERIFIED WITH MEDTRUM HARDWARE: нет.
- NOT VERIFIED: общий starvation fix, полная терапевтическая/аппаратная приёмка, Samsung SDK, полный production log budget.

Подпись APK должна совпасть с обычным пользовательским 3.4.2.3: SHA-256 сертификата `1b20d5c3807e9e6d895728d68099e21801ec05f860d4cc457eee25e530a8a084`. Совпадение сертификата и успешная сборка не доказывают безопасность терапии.
