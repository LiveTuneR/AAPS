# Apex7 bolus safety: итог реализации

Статус: `EXPERIMENTAL_DEVICE_VALIDATION_REQUIRED`.

## Реализовано

- Durable FSM разделяет `Completed` и подтверждение постоянной историей. Журнал сохраняется до первого BLE write и fail-closed восстанавливается после restart, включая повреждённый файл.
- Неоднозначный bolus не повторяется автоматически. Unresolved gate блокирует все bolus-источники, extended bolus и повышающий TBR; чтение, reconciliation и уменьшающие действия остаются доступны.
- Reconciliation: две ограниченные попытки LatestBoluses, затем полный BolusHistory; совпадение использует дозу в шагах, временную опору и identity помпы. Stale/non-monotonic/duplicate/cursor anomalies логируются.
- Runtime `inProgressBolus` отделён от durable uncertainty, поэтому busy не зависает. Overview и Apex diagnostics показывают requested/live/history/state/gate.
- Телеметрия получила durable admission/commit WAL, time-ranged integrity ledger, фазово-корректный CGM gap detector, индекс gzip-сегментов и единый 1/4/7-day support bundle с AAPS logs, Apex trace и operation journal.
- Добавлены replay реального случая 9.25 U / 370 steps, тесты normal/partial/cancel/mismatch/stale/restart/no-resend и OFF-BODY протокол приемки.

## Автоматически проверено

- Kotlin production compilation: PASS.
- ApexBolusReconciliationTest: 9/9 PASS.
- ApexBolusRestartSafetyTest: 9/9 PASS.
- ApexHistoryAnomalyTest и ApexCommDirectorTest: PASS.
- Новые telemetry tests: phase offset, ranged integrity, restart admission loss: 3/3 PASS.
- Полный Linux CI, phone/Wear release build и проверка подписи заполняются ссылкой и хешами после GitHub run.

## Не проверено физически

- Реальная работа мотора и фактически доставленный объём Apex.
- Поведение постоянной истории конкретной прошивки `1.1.1.0 / 4.12`.
- BLE fault windows и process-kill во время реальной подачи.
- Работа Medtrum hardware.

До просмотра физических логов сборка не является клинически принятой. Приёмка выполняется только OFF-BODY по `doc/APEX_BOLUS_DEVICE_ACCEPTANCE_RU.md`.
