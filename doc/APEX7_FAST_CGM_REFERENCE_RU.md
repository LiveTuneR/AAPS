# Fast CGM и экран по референсу

База: `ec8f4f571ec940e89f50a5877a2d33b0307360e7`.
Дата: 2026-09-12. Статус: PARTIAL до окончания UI/CI приёмки.
Любой APK: **EXPERIMENTAL_NOT_DEVICE_VALIDATED**.

## A. Причина и изменение

На нашей ветке clone уже не переносит referenceTime. Поэтому законченный расчёт
с публикацией копии не воспроизводит весь описанный сторонним автором дефект.
Но повторная загрузка того же live ADS после пропущенной публикации сохраняла
anchor предыдущего прохода. Этот путь воспроизведён до изменения production-кода.

createBucketedData теперь сбрасывает referenceTime в finally: обычное завершение,
ранний выход и исключение не оставляют anchor следующему проходу.
clone по-прежнему не переносит anchor; глубокие копии и generation-guards сохранены.
Уравнения APS, лимиты SMB, команды Apex/Medtrum и доставка инсулина не менялись.

### Сравнение с частным решением

Репозиторий dev4_main_cust / 5a629bdaf7 закрыт и недоступен.
Проверено описание и фрагменты из `aaps-loop-5min-fix.html`, а не сам коммит.
Перенесена только недостающая семантика per-pass finally. Re-anchor/anchorShift
варианты из чужого дерева у нас отсутствуют, их удаление не требовалось.
Наблюдения 119 -> 292 секунд и 46/47 timestamps на сетке являются сообщением автора,
не собственным измерением телефона. Upstream 17dd2bbd8e не откатывался целиком.

### Поведенческие тесты

FastCgmAnchorTest: 8 тестов. До исправления 4 падения; после 0.
60 секунд: 31 вход за 30 минут, 120 секунд: 16 входов за 30 минут.
Начало 12:01:17, каждый новый timestamp/value совпадает с новым bucket.
Проверены порядок, отсутствие будущих/дублированных bucket, completed-copy и
superseded/live reload, 5 минут точно/с jitter 10 секунд и смена фазы сенсора.
Это проверка данных на входе loop, не 31/16 фактических введений инсулина.
Защита одинакового BG проверяется отдельно существующим PostCalculationWorkerTest;
публикация старого поколения и мутабельная изоляция отдельными существующими тестами.

### Мутации (каждая применена отдельно и затем полностью удалена)

M1: вернуть `it.referenceTime = this.referenceTime` в clone. 1 падение:
- `clone never imports an unfinished or legacy pass anchor()`

M2: clone не переносит anchor, убрать finally-reset. 4 падения:
- `superseded publication followed by live reload keeps newest 120 second BG()`
- `superseded publication followed by live reload keeps newest 60 second BG()`
- `anchor ends at pass boundary including empty input()`
- `sensor restart with a new phase does not inherit the previous anchor()`

M3: заменить adjustToReferenceTime на округление вниз к epoch-сетке 300000 ms.
4 падения:
- `60 second CGM advances every completed publication for 30 minutes()`
- `120 second CGM advances every completed publication for 30 minutes()`
- `superseded publication followed by live reload keeps newest 120 second BG()`
- `superseded publication followed by live reload keeps newest 60 second BG()`

Локальные исходные XML: build/reports/apex7-anchor-evidence/{before,after,m1,m2,m3}.xml.
Падения являются ожидаемым доказательством эффективности тестов, не зелёным CI.

## Независимый эксперимент планировщика

ContinuousCgmWorkflowTest вызывает настоящий CalculationWorkflowImpl.runCalculation,
проверяет REPLACE, request inputData/generation и настоящий WorkflowChainData.
WorkManager заменён тестовым delegate; время выполнения синтетическое. Завершения
проверяются через настоящий publishIfCurrent/postFor. Это не Android scheduler/CPU
benchmark и не запуск полного oref/помпы. Эксперимент определяет допускает ли сама
политика прогресс при заданных длительностях; не утверждает, что телефон так медленен.

На горизонте 30 минут подаётся 31 BG, включая последний в конце. Тихого хвоста нет.

| Длительность | Принято публикаций | Отклонено | Ещё не завершено |
|---|---:|---:|---:|
| 50 с, контроль | 30 | 0 | 1 |
| 70 с | 0 | 29 | 2 |
| 90 с | 0 | 29 | 2 |
| 120 с | 0 | 29 | 2 |

Для 70/90/120 проверены своевременная отмена и игнорирование отмены. В обоих
случаях 0 публикаций: generation-guard правильно отвергает вытеснённый результат.
Следовательно starvation возможен независимо от исправления bucket.

### Предложение, НЕ реализовано

Один running job плюс один pending-latest запрос. Новые BG обновляют pending,
не отменяя активный согласованный снимок. По завершении running стартует последний
pending. Нельзя просто поставить KEEP: нынешний startMain всё равно меняет slot.
Нельзя просто APPEND: это накопит очередь устаревших расчётов.

Нужны отдельные calculation generation и therapy epoch, согласованный снимок
профиля/лечения/ADS, атомарный handoff без потери pending, freshness gate перед
loop и отдельное инвалидирование при изменении терапии. Сохраняется watermark
уникального BG. Исторические экраны не должны менять main ownership.
Необходимы crash/restart, cancellation, therapy-change, lost-wakeup, duplicate BG
и 60/120-second continuous tests до принятия такой правки.

При throughput ниже частоты входа невозможно рассчитать каждый вход без отставания.
Pending-latest должен явно учитывать пропуски промежуточных BG, а не обещать по
инсулиновому решению на каждый вход. Для строгого every-BG потребуется ускорение
расчёта либо доказанная безопасная инкрементальная модель, это отдельная задача.

## B. UI

Реализация и визуальная приёмка в отдельном коммите. Сохранён переключатель
Enhanced Overview; обычный экран и NSClient остаются отдельным путём.
Activity read-only. Финальный AutoISF factor не выдумывается: нет отдельного
структурированного поля, поэтому показывается --, trace доступен в деталях.
Дальнейшие скриншоты, JUnit и provenance записываются после завершения проверки.
